/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.runtime.scheduler.adaptivebatch;

import org.apache.flink.api.common.BatchShuffleMode;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.JobManagerOptions.HybridPartitionDataConsumeConstraint;
import org.apache.flink.runtime.blob.BlobWriter;
import org.apache.flink.runtime.blocklist.BlocklistOperations;
import org.apache.flink.runtime.checkpoint.CheckpointRecoveryFactory;
import org.apache.flink.runtime.checkpoint.CheckpointsCleaner;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.executiongraph.SpeculativeExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.failover.flip1.FailoverStrategyFactoryLoader;
import org.apache.flink.runtime.executiongraph.failover.flip1.RestartBackoffTimeStrategy;
import org.apache.flink.runtime.executiongraph.failover.flip1.RestartBackoffTimeStrategyFactoryLoader;
import org.apache.flink.runtime.io.network.partition.JobMasterPartitionTracker;
import org.apache.flink.runtime.jobgraph.IntermediateDataSet;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobType;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobmaster.ExecutionDeploymentTracker;
import org.apache.flink.runtime.jobmaster.slotpool.PhysicalSlotProvider;
import org.apache.flink.runtime.jobmaster.slotpool.PhysicalSlotProviderImpl;
import org.apache.flink.runtime.jobmaster.slotpool.SlotPool;
import org.apache.flink.runtime.jobmaster.slotpool.SlotPoolService;
import org.apache.flink.runtime.jobmaster.slotpool.SlotSelectionStrategy;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.scheduler.DefaultExecutionGraphFactory;
import org.apache.flink.runtime.scheduler.DefaultExecutionOperations;
import org.apache.flink.runtime.scheduler.ExecutionGraphFactory;
import org.apache.flink.runtime.scheduler.ExecutionSlotAllocatorFactory;
import org.apache.flink.runtime.scheduler.ExecutionVertexVersioner;
import org.apache.flink.runtime.scheduler.SchedulerNG;
import org.apache.flink.runtime.scheduler.SchedulerNGFactory;
import org.apache.flink.runtime.scheduler.SimpleExecutionSlotAllocator;
import org.apache.flink.runtime.scheduler.strategy.AllFinishedInputConsumableDecider;
import org.apache.flink.runtime.scheduler.strategy.DefaultInputConsumableDecider;
import org.apache.flink.runtime.scheduler.strategy.InputConsumableDecider;
import org.apache.flink.runtime.scheduler.strategy.PartialFinishedInputConsumableDecider;
import org.apache.flink.runtime.scheduler.strategy.SchedulingStrategyFactory;
import org.apache.flink.runtime.scheduler.strategy.VertexwiseSchedulingStrategy;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.util.SlotSelectionStrategyUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.ScheduledExecutorServiceAdapter;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.apache.flink.configuration.JobManagerOptions.HybridPartitionDataConsumeConstraint.ALL_PRODUCERS_FINISHED;
import static org.apache.flink.configuration.JobManagerOptions.HybridPartitionDataConsumeConstraint.ONLY_FINISHED_PRODUCERS;
import static org.apache.flink.configuration.JobManagerOptions.HybridPartitionDataConsumeConstraint.UNFINISHED_PRODUCERS;
import static org.apache.flink.util.Preconditions.checkState;

/** Factory for {@link AdaptiveBatchScheduler}. */
public class AdaptiveBatchSchedulerFactory implements SchedulerNGFactory {

    @Override
    public SchedulerNG createInstance(
            Logger log,
            JobGraph jobGraph,
            Executor ioExecutor,
            Configuration jobMasterConfiguration,
            SlotPoolService slotPoolService,
            ScheduledExecutorService futureExecutor,
            ClassLoader userCodeLoader,
            CheckpointRecoveryFactory checkpointRecoveryFactory,
            Time rpcTimeout,
            BlobWriter blobWriter,
            JobManagerJobMetricGroup jobManagerJobMetricGroup,
            Time slotRequestTimeout,
            ShuffleMaster<?> shuffleMaster,
            JobMasterPartitionTracker partitionTracker,
            ExecutionDeploymentTracker executionDeploymentTracker,
            long initializationTimestamp,
            ComponentMainThreadExecutor mainThreadExecutor,
            FatalErrorHandler fatalErrorHandler,
            JobStatusListener jobStatusListener,
            BlocklistOperations blocklistOperations)
            throws Exception {

        checkState(
                jobGraph.getJobType() == JobType.BATCH,
                "Adaptive batch scheduler only supports batch jobs");
        checkAllExchangesAreSupported(jobGraph);
        final SlotPool slotPool =
                slotPoolService
                        .castInto(SlotPool.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "The AdaptiveBatchScheduler requires a SlotPool."));

        final boolean enableSpeculativeExecution =
                jobMasterConfiguration.getBoolean(JobManagerOptions.SPECULATIVE_ENABLED);

        final HybridPartitionDataConsumeConstraint hybridPartitionDataConsumeConstraint =
                getOrDecideHybridPartitionDataConsumeConstraint(
                        jobMasterConfiguration, enableSpeculativeExecution);

        final List<Consumer<ComponentMainThreadExecutor>> startUpActions = new ArrayList<>();
        final Consumer<ComponentMainThreadExecutor> combinedStartUpActions =
                m -> startUpActions.forEach(a -> a.accept(m));

        final ExecutionSlotAllocatorFactory allocatorFactory =
                createExecutionSlotAllocatorFactory(jobMasterConfiguration, slotPool);

        final RestartBackoffTimeStrategy restartBackoffTimeStrategy =
                RestartBackoffTimeStrategyFactoryLoader.createRestartBackoffTimeStrategyFactory(
                                jobGraph.getSerializedExecutionConfig()
                                        .deserializeValue(userCodeLoader)
                                        .getRestartStrategy(),
                                jobMasterConfiguration,
                                jobGraph.isCheckpointingEnabled())
                        .create();
        log.info(
                "Using restart back off time strategy {} for {} ({}).",
                restartBackoffTimeStrategy,
                jobGraph.getName(),
                jobGraph.getJobID());

        final ExecutionGraphFactory executionGraphFactory =
                new DefaultExecutionGraphFactory(
                        jobMasterConfiguration,
                        userCodeLoader,
                        executionDeploymentTracker,
                        futureExecutor,
                        ioExecutor,
                        rpcTimeout,
                        jobManagerJobMetricGroup,
                        blobWriter,
                        shuffleMaster,
                        partitionTracker,
                        true,
                        createExecutionJobVertexFactory(enableSpeculativeExecution),
                        hybridPartitionDataConsumeConstraint == ONLY_FINISHED_PRODUCERS);

        final SchedulingStrategyFactory schedulingStrategyFactory =
                new VertexwiseSchedulingStrategy.Factory(
                        loadInputConsumableDeciderFactory(hybridPartitionDataConsumeConstraint));

        if (enableSpeculativeExecution) {
            return new SpeculativeScheduler(
                    log,
                    jobGraph,
                    ioExecutor,
                    jobMasterConfiguration,
                    combinedStartUpActions,
                    new ScheduledExecutorServiceAdapter(futureExecutor),
                    userCodeLoader,
                    new CheckpointsCleaner(),
                    checkpointRecoveryFactory,
                    jobManagerJobMetricGroup,
                    schedulingStrategyFactory,
                    FailoverStrategyFactoryLoader.loadFailoverStrategyFactory(
                            jobMasterConfiguration),
                    restartBackoffTimeStrategy,
                    new DefaultExecutionOperations(),
                    new ExecutionVertexVersioner(),
                    allocatorFactory,
                    initializationTimestamp,
                    mainThreadExecutor,
                    jobStatusListener,
                    executionGraphFactory,
                    shuffleMaster,
                    rpcTimeout,
                    DefaultVertexParallelismAndInputInfosDecider.from(jobMasterConfiguration),
                    jobMasterConfiguration.getInteger(
                            JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MAX_PARALLELISM),
                    blocklistOperations,
                    hybridPartitionDataConsumeConstraint);
        } else {
            return new AdaptiveBatchScheduler(
                    log,
                    jobGraph,
                    ioExecutor,
                    jobMasterConfiguration,
                    combinedStartUpActions,
                    new ScheduledExecutorServiceAdapter(futureExecutor),
                    userCodeLoader,
                    new CheckpointsCleaner(),
                    checkpointRecoveryFactory,
                    jobManagerJobMetricGroup,
                    schedulingStrategyFactory,
                    FailoverStrategyFactoryLoader.loadFailoverStrategyFactory(
                            jobMasterConfiguration),
                    restartBackoffTimeStrategy,
                    new DefaultExecutionOperations(),
                    new ExecutionVertexVersioner(),
                    allocatorFactory,
                    initializationTimestamp,
                    mainThreadExecutor,
                    jobStatusListener,
                    executionGraphFactory,
                    shuffleMaster,
                    rpcTimeout,
                    DefaultVertexParallelismAndInputInfosDecider.from(jobMasterConfiguration),
                    jobMasterConfiguration.getInteger(
                            JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MAX_PARALLELISM),
                    hybridPartitionDataConsumeConstraint);
        }
    }

    public static InputConsumableDecider.Factory loadInputConsumableDeciderFactory(
            HybridPartitionDataConsumeConstraint hybridPartitionDataConsumeConstraint) {
        switch (hybridPartitionDataConsumeConstraint) {
            case ALL_PRODUCERS_FINISHED:
                return AllFinishedInputConsumableDecider.Factory.INSTANCE;
            case ONLY_FINISHED_PRODUCERS:
                return PartialFinishedInputConsumableDecider.Factory.INSTANCE;
            case UNFINISHED_PRODUCERS:
                return DefaultInputConsumableDecider.Factory.INSTANCE;
            default:
                throw new IllegalStateException(
                        hybridPartitionDataConsumeConstraint + "is not supported.");
        }
    }

    public static HybridPartitionDataConsumeConstraint
            getOrDecideHybridPartitionDataConsumeConstraint(
                    Configuration configuration, boolean enableSpeculativeExecution) {
        final HybridPartitionDataConsumeConstraint hybridPartitionDataConsumeConstraint =
                configuration
                        .getOptional(JobManagerOptions.HYBRID_PARTITION_DATA_CONSUME_CONSTRAINT)
                        .orElse(
                                enableSpeculativeExecution
                                        ? ALL_PRODUCERS_FINISHED
                                        : UNFINISHED_PRODUCERS);
        if (enableSpeculativeExecution) {
            Preconditions.checkState(
                    hybridPartitionDataConsumeConstraint != UNFINISHED_PRODUCERS,
                    "For speculative execution, only supports consume finished partition now.");
        }
        return hybridPartitionDataConsumeConstraint;
    }

    private static ExecutionSlotAllocatorFactory createExecutionSlotAllocatorFactory(
            Configuration configuration, SlotPool slotPool) {
        final SlotSelectionStrategy slotSelectionStrategy =
                SlotSelectionStrategyUtils.selectSlotSelectionStrategy(
                        JobType.BATCH, configuration);
        final PhysicalSlotProvider physicalSlotProvider =
                new PhysicalSlotProviderImpl(slotSelectionStrategy, slotPool);

        return new SimpleExecutionSlotAllocator.Factory(physicalSlotProvider, false);
    }

    private static ExecutionJobVertex.Factory createExecutionJobVertexFactory(
            boolean enableSpeculativeExecution) {
        if (enableSpeculativeExecution) {
            return new SpeculativeExecutionJobVertex.Factory();
        } else {
            return new ExecutionJobVertex.Factory();
        }
    }

    private void checkAllExchangesAreSupported(final JobGraph jobGraph) {
        for (JobVertex jobVertex : jobGraph.getVertices()) {
            for (IntermediateDataSet dataSet : jobVertex.getProducedDataSets()) {
                checkState(
                        dataSet.getResultType().isBlockingOrBlockingPersistentResultPartition()
                                || dataSet.getResultType().isHybridResultPartition(),
                        String.format(
                                "At the moment, adaptive batch scheduler requires batch workloads "
                                        + "to be executed with types of all edges being BLOCKING or HYBRID_FULL/HYBRID_SELECTIVE. "
                                        + "To do that, you need to configure '%s' to '%s' or '%s/%s'.",
                                ExecutionOptions.BATCH_SHUFFLE_MODE.key(),
                                BatchShuffleMode.ALL_EXCHANGES_BLOCKING,
                                BatchShuffleMode.ALL_EXCHANGES_HYBRID_FULL,
                                BatchShuffleMode.ALL_EXCHANGES_HYBRID_SELECTIVE));
            }
        }
    }

    @Override
    public JobManagerOptions.SchedulerType getSchedulerType() {
        return JobManagerOptions.SchedulerType.AdaptiveBatch;
    }
}
