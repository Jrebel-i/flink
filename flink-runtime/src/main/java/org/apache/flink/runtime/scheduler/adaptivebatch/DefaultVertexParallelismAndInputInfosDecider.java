/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.scheduler.adaptivebatch;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.runtime.executiongraph.ExecutionVertexInputInfo;
import org.apache.flink.runtime.executiongraph.IndexRange;
import org.apache.flink.runtime.executiongraph.JobVertexInputInfo;
import org.apache.flink.runtime.executiongraph.ParallelismAndInputInfos;
import org.apache.flink.runtime.executiongraph.VertexInputInfoComputationUtils;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Default implementation of {@link VertexParallelismAndInputInfosDecider}. This implementation will
 * decide parallelism and {@link JobVertexInputInfo}s as follows:
 *
 * <p>1. For job vertices whose inputs are all ALL_TO_ALL edges, evenly distribute data to
 * downstream subtasks, make different downstream subtasks consume roughly the same amount of data.
 *
 * <p>2. For other cases, evenly distribute subpartitions to downstream subtasks, make different
 * downstream subtasks consume roughly the same number of subpartitions.
 */
public class DefaultVertexParallelismAndInputInfosDecider
        implements VertexParallelismAndInputInfosDecider {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultVertexParallelismAndInputInfosDecider.class);

    /**
     * The cap ratio of broadcast bytes to data volume per task. The cap ratio is 0.5 currently
     * because we usually expect the broadcast dataset to be smaller than non-broadcast. We can make
     * it configurable later if we see users requesting for it.
     */
    private static final double CAP_RATIO_OF_BROADCAST = 0.5;

    private final int maxParallelism;
    private final int minParallelism;
    private final long dataVolumePerTask;
    private final int defaultSourceParallelism;

    private DefaultVertexParallelismAndInputInfosDecider(
            int maxParallelism,
            int minParallelism,
            MemorySize dataVolumePerTask,
            int defaultSourceParallelism) {

        checkArgument(minParallelism > 0, "The minimum parallelism must be larger than 0.");
        checkArgument(
                maxParallelism >= minParallelism,
                "Maximum parallelism should be greater than or equal to the minimum parallelism.");
        checkArgument(
                defaultSourceParallelism > 0,
                "The default source parallelism must be larger than 0.");
        checkNotNull(dataVolumePerTask);

        this.maxParallelism = maxParallelism;
        this.minParallelism = minParallelism;
        this.dataVolumePerTask = dataVolumePerTask.getBytes();
        this.defaultSourceParallelism = defaultSourceParallelism;
    }

    @Override
    public ParallelismAndInputInfos decideParallelismAndInputInfosForVertex(
            JobVertexID jobVertexId,
            List<BlockingResultInfo> consumedResults,
            int initialParallelism) {
        checkArgument(
                initialParallelism == ExecutionConfig.PARALLELISM_DEFAULT
                        || initialParallelism > 0);

        if (consumedResults.isEmpty()) {
            // source job vertex
            int parallelism =
                    initialParallelism > 0 ? initialParallelism : defaultSourceParallelism;
            return new ParallelismAndInputInfos(parallelism, Collections.emptyMap());
        } else if (initialParallelism == ExecutionConfig.PARALLELISM_DEFAULT
                && areAllInputsAllToAll(consumedResults)
                && !areAllInputsBroadcast(consumedResults)) {
            return decideParallelismAndEvenlyDistributeData(
                    jobVertexId, consumedResults, initialParallelism);
        } else {
            return decideParallelismAndEvenlyDistributeSubpartitions(
                    jobVertexId, consumedResults, initialParallelism);
        }
    }

    private static boolean areAllInputsAllToAll(List<BlockingResultInfo> consumedResults) {
        return consumedResults.stream().noneMatch(BlockingResultInfo::isPointwise);
    }

    private static boolean areAllInputsBroadcast(List<BlockingResultInfo> consumedResults) {
        return consumedResults.stream().allMatch(BlockingResultInfo::isBroadcast);
    }

    /**
     * Decide parallelism and input infos, which will make the subpartitions be evenly distributed
     * to downstream subtasks, such that different downstream subtasks consume roughly the same
     * number of subpartitions.
     *
     * @param jobVertexId The job vertex id
     * @param consumedResults The information of consumed blocking results
     * @param initialParallelism The initial parallelism of the job vertex
     * @return the parallelism and vertex input infos
     */
    private ParallelismAndInputInfos decideParallelismAndEvenlyDistributeSubpartitions(
            JobVertexID jobVertexId,
            List<BlockingResultInfo> consumedResults,
            int initialParallelism) {
        checkArgument(!consumedResults.isEmpty());
        int parallelism =
                initialParallelism > 0
                        ? initialParallelism
                        : decideParallelism(jobVertexId, consumedResults);
        return new ParallelismAndInputInfos(
                parallelism,
                VertexInputInfoComputationUtils.computeVertexInputInfos(
                        parallelism, consumedResults, true));
    }

    int decideParallelism(JobVertexID jobVertexId, List<BlockingResultInfo> consumedResults) {
        checkArgument(!consumedResults.isEmpty());

        long broadcastBytes = getReasonableBroadcastBytes(jobVertexId, consumedResults);
        long nonBroadcastBytes = getNonBroadcastBytes(consumedResults);

        int parallelism =
                (int) Math.ceil((double) nonBroadcastBytes / (dataVolumePerTask - broadcastBytes));

        LOG.debug(
                "The size of broadcast data is {}, the size of non-broadcast data is {}, "
                        + "the initially decided parallelism of job vertex {} is {}.",
                new MemorySize(broadcastBytes),
                new MemorySize(nonBroadcastBytes),
                jobVertexId,
                parallelism);

        if (parallelism < minParallelism) {
            LOG.info(
                    "The initially decided parallelism {} is smaller than the minimum parallelism {}. "
                            + "Use {} as the finally decided parallelism of job vertex {}.",
                    parallelism,
                    minParallelism,
                    minParallelism,
                    jobVertexId);
            parallelism = minParallelism;
        } else if (parallelism > maxParallelism) {
            LOG.info(
                    "The initially decided parallelism {} is larger than the maximum parallelism {}. "
                            + "Use {} as the finally decided parallelism of job vertex {}.",
                    parallelism,
                    maxParallelism,
                    maxParallelism,
                    jobVertexId);
            parallelism = maxParallelism;
        }

        return parallelism;
    }

    /**
     * Decide parallelism and input infos, which will make the data be evenly distributed to
     * downstream subtasks, such that different downstream subtasks consume roughly the same amount
     * of data.
     *
     * @param jobVertexId The job vertex id
     * @param consumedResults The information of consumed blocking results
     * @param initialParallelism The initial parallelism of the job vertex
     * @return the parallelism and vertex input infos
     */
    private ParallelismAndInputInfos decideParallelismAndEvenlyDistributeData(
            JobVertexID jobVertexId,
            List<BlockingResultInfo> consumedResults,
            int initialParallelism) {
        checkArgument(initialParallelism == ExecutionConfig.PARALLELISM_DEFAULT);
        checkArgument(!consumedResults.isEmpty());
        consumedResults.forEach(resultInfo -> checkState(!resultInfo.isPointwise()));

        final List<BlockingResultInfo> nonBroadcastResults =
                getNonBroadcastResultInfos(consumedResults);
        long broadcastBytes = getReasonableBroadcastBytes(jobVertexId, consumedResults);
        int subpartitionNum = checkAndGetSubpartitionNum(nonBroadcastResults);

        long nonBroadcastDataVolumeLimit = dataVolumePerTask - broadcastBytes;
        long[] nonBroadcastBytesBySubpartition = new long[subpartitionNum];
        Arrays.fill(nonBroadcastBytesBySubpartition, 0L);
        for (BlockingResultInfo resultInfo : nonBroadcastResults) {
            List<Long> subpartitionBytes =
                    ((AllToAllBlockingResultInfo) resultInfo).getAggregatedSubpartitionBytes();
            for (int i = 0; i < subpartitionNum; ++i) {
                nonBroadcastBytesBySubpartition[i] += subpartitionBytes.get(i);
            }
        }

        // compute subpartition ranges
        List<IndexRange> subpartitionRanges =
                computeSubpartitionRanges(
                        nonBroadcastBytesBySubpartition, nonBroadcastDataVolumeLimit);

        // if the parallelism is not legal, adjust to a legal parallelism
        if (!isLegalParallelism(subpartitionRanges.size())) {
            Optional<List<IndexRange>> adjustedSubpartitionRanges =
                    adjustToClosestLegalParallelism(
                            nonBroadcastBytesBySubpartition,
                            nonBroadcastDataVolumeLimit,
                            subpartitionRanges.size());
            if (!adjustedSubpartitionRanges.isPresent()) {
                // can't find any legal parallelism, fall back to evenly distribute subpartitions
                LOG.info(
                        "Cannot find a legal parallelism to evenly distribute data for job vertex {}. "
                                + "Fall back to compute a parallelism that can evenly distribute subpartitions.",
                        jobVertexId);
                return decideParallelismAndEvenlyDistributeSubpartitions(
                        jobVertexId, consumedResults, initialParallelism);
            }
            subpartitionRanges = adjustedSubpartitionRanges.get();
        }

        checkState(isLegalParallelism(subpartitionRanges.size()));
        return createParallelismAndInputInfos(consumedResults, subpartitionRanges);
    }

    private boolean isLegalParallelism(int parallelism) {
        return parallelism >= minParallelism && parallelism <= maxParallelism;
    }

    private static int checkAndGetSubpartitionNum(List<BlockingResultInfo> consumedResults) {
        final Set<Integer> subpartitionNumSet =
                consumedResults.stream()
                        .flatMap(
                                resultInfo ->
                                        IntStream.range(0, resultInfo.getNumPartitions())
                                                .boxed()
                                                .map(resultInfo::getNumSubpartitions))
                        .collect(Collectors.toSet());
        // all partitions have the same subpartition num
        checkState(subpartitionNumSet.size() == 1);
        return subpartitionNumSet.iterator().next();
    }

    /**
     * Adjust the parallelism to the closest legal parallelism and return the computed subpartition
     * ranges.
     *
     * @param bytesBySubpartition the bytes of subpartition
     * @param currentDataVolumeLimit current data volume limit
     * @param currentParallelism current parallelism
     * @return the computed subpartition ranges or {@link Optional#empty()} if we can't find any
     *     legal parallelism
     */
    private Optional<List<IndexRange>> adjustToClosestLegalParallelism(
            long[] bytesBySubpartition, long currentDataVolumeLimit, int currentParallelism) {
        long adjustedDataVolumeLimit = currentDataVolumeLimit;
        if (currentParallelism < minParallelism) {
            long minSubpartitionBytes = Arrays.stream(bytesBySubpartition).min().getAsLong();
            // Current parallelism is smaller than the user-specified lower-limit of parallelism ,
            // we need to adjust it to the closest/minimum possible legal parallelism. That is, we
            // need to find the maximum legal dataVolumeLimit.
            adjustedDataVolumeLimit =
                    BisectionSearchUtils.findMaxLegalValue(
                            value ->
                                    computeParallelism(bytesBySubpartition, value)
                                            >= minParallelism,
                            minSubpartitionBytes,
                            currentDataVolumeLimit);

            // When we find the minimum possible legal parallelism, the dataVolumeLimit that can
            // lead to this parallelism may be a range, and we need to find the minimum value of
            // this range to make the data distribution as even as possible (the smaller the
            // dataVolumeLimit, the more even the distribution)
            final long minPossibleLegalParallelism =
                    computeParallelism(bytesBySubpartition, adjustedDataVolumeLimit);
            adjustedDataVolumeLimit =
                    BisectionSearchUtils.findMinLegalValue(
                            value ->
                                    computeParallelism(bytesBySubpartition, value)
                                            == minPossibleLegalParallelism,
                            minSubpartitionBytes,
                            adjustedDataVolumeLimit);

        } else if (currentParallelism > maxParallelism) {
            long totalBytes = Arrays.stream(bytesBySubpartition).sum();
            // Current parallelism is larger than the user-specified upper-limit of parallelism ,
            // we need to adjust it to the closest/maximum possible legal parallelism. That is, we
            // need to find the minimum legal dataVolumeLimit.
            adjustedDataVolumeLimit =
                    BisectionSearchUtils.findMinLegalValue(
                            value ->
                                    computeParallelism(bytesBySubpartition, value)
                                            <= maxParallelism,
                            currentDataVolumeLimit,
                            totalBytes);
        }

        int adjustedParallelism = computeParallelism(bytesBySubpartition, adjustedDataVolumeLimit);
        if (isLegalParallelism(adjustedParallelism)) {
            return Optional.of(
                    computeSubpartitionRanges(bytesBySubpartition, adjustedDataVolumeLimit));
        } else {
            return Optional.empty();
        }
    }

    private static ParallelismAndInputInfos createParallelismAndInputInfos(
            List<BlockingResultInfo> consumedResults, List<IndexRange> subpartitionRanges) {

        final Map<IntermediateDataSetID, JobVertexInputInfo> vertexInputInfos = new HashMap<>();
        consumedResults.forEach(
                resultInfo -> {
                    int sourceParallelism = resultInfo.getNumPartitions();
                    IndexRange partitionRange = new IndexRange(0, sourceParallelism - 1);

                    List<ExecutionVertexInputInfo> executionVertexInputInfos = new ArrayList<>();
                    for (int i = 0; i < subpartitionRanges.size(); ++i) {
                        IndexRange subpartitionRange;
                        if (resultInfo.isBroadcast()) {
                            subpartitionRange = new IndexRange(0, 0);
                        } else {
                            subpartitionRange = subpartitionRanges.get(i);
                        }
                        ExecutionVertexInputInfo executionVertexInputInfo =
                                new ExecutionVertexInputInfo(i, partitionRange, subpartitionRange);
                        executionVertexInputInfos.add(executionVertexInputInfo);
                    }

                    vertexInputInfos.put(
                            resultInfo.getResultId(),
                            new JobVertexInputInfo(executionVertexInputInfos));
                });
        return new ParallelismAndInputInfos(subpartitionRanges.size(), vertexInputInfos);
    }

    private static List<IndexRange> computeSubpartitionRanges(long[] nums, long limit) {
        List<IndexRange> subpartitionRanges = new ArrayList<>();
        long tmpSum = 0;
        int startIndex = 0;
        for (int i = 0; i < nums.length; ++i) {
            long num = nums[i];
            if (tmpSum == 0 || tmpSum + num <= limit) {
                tmpSum += num;
            } else {
                subpartitionRanges.add(new IndexRange(startIndex, i - 1));
                startIndex = i;
                tmpSum = num;
            }
        }
        subpartitionRanges.add(new IndexRange(startIndex, nums.length - 1));
        return subpartitionRanges;
    }

    private static int computeParallelism(long[] nums, long limit) {
        long tmpSum = 0;
        int count = 1;
        for (long num : nums) {
            if (tmpSum == 0 || tmpSum + num <= limit) {
                tmpSum += num;
            } else {
                tmpSum = num;
                count += 1;
            }
        }
        return count;
    }

    private long getNonBroadcastBytes(List<BlockingResultInfo> consumedResults) {
        return getNonBroadcastResultInfos(consumedResults).stream()
                .mapToLong(BlockingResultInfo::getNumBytesProduced)
                .sum();
    }

    private long getReasonableBroadcastBytes(
            JobVertexID jobVertexId, List<BlockingResultInfo> consumedResults) {
        long broadcastBytes =
                getBroadcastResultInfos(consumedResults).stream()
                        .mapToLong(BlockingResultInfo::getNumBytesProduced)
                        .sum();

        long expectedMaxBroadcastBytes =
                (long) Math.ceil((dataVolumePerTask * CAP_RATIO_OF_BROADCAST));

        if (broadcastBytes > expectedMaxBroadcastBytes) {
            LOG.info(
                    "The size of broadcast data {} is larger than the expected maximum value {} ('{}' * {})."
                            + " Use {} as the size of broadcast data to decide the parallelism of job vertex {}.",
                    new MemorySize(broadcastBytes),
                    new MemorySize(expectedMaxBroadcastBytes),
                    JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_AVG_DATA_VOLUME_PER_TASK.key(),
                    CAP_RATIO_OF_BROADCAST,
                    new MemorySize(expectedMaxBroadcastBytes),
                    jobVertexId);

            broadcastBytes = expectedMaxBroadcastBytes;
        }

        return broadcastBytes;
    }

    private static List<BlockingResultInfo> getBroadcastResultInfos(
            List<BlockingResultInfo> consumedResults) {
        return consumedResults.stream()
                .filter(BlockingResultInfo::isBroadcast)
                .collect(Collectors.toList());
    }

    private static List<BlockingResultInfo> getNonBroadcastResultInfos(
            List<BlockingResultInfo> consumedResults) {
        return consumedResults.stream()
                .filter(resultInfo -> !resultInfo.isBroadcast())
                .collect(Collectors.toList());
    }

    static DefaultVertexParallelismAndInputInfosDecider from(Configuration configuration) {
        return new DefaultVertexParallelismAndInputInfosDecider(
                configuration.getInteger(
                        JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MAX_PARALLELISM),
                configuration.getInteger(
                        JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MIN_PARALLELISM),
                configuration.get(
                        JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_AVG_DATA_VOLUME_PER_TASK),
                configuration.get(
                        JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_DEFAULT_SOURCE_PARALLELISM));
    }
}
