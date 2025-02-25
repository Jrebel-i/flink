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

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.runtime.executiongraph.ExecutionVertexInputInfo;
import org.apache.flink.runtime.executiongraph.IndexRange;
import org.apache.flink.runtime.executiongraph.JobVertexInputInfo;
import org.apache.flink.runtime.executiongraph.ParallelismAndInputInfos;
import org.apache.flink.runtime.executiongraph.ResultPartitionBytes;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import org.apache.flink.shaded.guava30.com.google.common.collect.Iterables;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link DefaultVertexParallelismAndInputInfosDecider}. */
class DefaultVertexParallelismAndInputInfosDeciderTest {

    private static final long BYTE_256_MB = 256 * 1024 * 1024L;
    private static final long BYTE_512_MB = 512 * 1024 * 1024L;
    private static final long BYTE_1_GB = 1024 * 1024 * 1024L;
    private static final long BYTE_8_GB = 8 * 1024 * 1024 * 1024L;
    private static final long BYTE_1_TB = 1024 * 1024 * 1024 * 1024L;

    private static final int MAX_PARALLELISM = 100;
    private static final int MIN_PARALLELISM = 3;
    private static final int DEFAULT_SOURCE_PARALLELISM = 10;
    private static final long DATA_VOLUME_PER_TASK = 1024 * 1024 * 1024L;

    @Test
    void testDecideParallelism() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        BlockingResultInfo resultInfo1 = createFromBroadcastResult(BYTE_256_MB);
        BlockingResultInfo resultInfo2 = createFromNonBroadcastResult(BYTE_256_MB + BYTE_8_GB);

        int parallelism =
                decider.decideParallelism(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2));

        assertThat(parallelism).isEqualTo(11);
    }

    @Test
    void testInitiallyNormalizedParallelismIsLargerThanMaxParallelism() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        BlockingResultInfo resultInfo1 = createFromBroadcastResult(BYTE_256_MB);
        BlockingResultInfo resultInfo2 = createFromNonBroadcastResult(BYTE_8_GB + BYTE_1_TB);

        int parallelism =
                decider.decideParallelism(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2));

        assertThat(parallelism).isEqualTo(MAX_PARALLELISM);
    }

    @Test
    void testInitiallyNormalizedParallelismIsSmallerThanMinParallelism() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        BlockingResultInfo resultInfo1 = createFromBroadcastResult(BYTE_256_MB);
        BlockingResultInfo resultInfo2 = createFromNonBroadcastResult(BYTE_512_MB);

        int parallelism =
                decider.decideParallelism(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2));

        assertThat(parallelism).isEqualTo(MIN_PARALLELISM);
    }

    @Test
    void testBroadcastRatioExceedsCapRatio() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        BlockingResultInfo resultInfo1 = createFromBroadcastResult(BYTE_1_GB);
        BlockingResultInfo resultInfo2 = createFromNonBroadcastResult(BYTE_8_GB);

        int parallelism =
                decider.decideParallelism(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2));

        assertThat(parallelism).isEqualTo(16);
    }

    @Test
    void testNonBroadcastBytesCanNotDividedEvenly() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        BlockingResultInfo resultInfo1 = createFromBroadcastResult(BYTE_512_MB);
        BlockingResultInfo resultInfo2 = createFromNonBroadcastResult(BYTE_256_MB + BYTE_8_GB);

        int parallelism =
                decider.decideParallelism(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2));

        assertThat(parallelism).isEqualTo(17);
    }

    @Test
    void testAllEdgesAllToAll() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(1, 10, 60L);

        AllToAllBlockingResultInfo resultInfo1 =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        AllToAllBlockingResultInfo resultInfo2 =
                createAllToAllBlockingResultInfo(
                        new long[] {8L, 12L, 21L, 9L, 13L, 7L, 19L, 13L, 14L, 5L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(5);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(2);

        List<IndexRange> subpartitionRanges =
                Arrays.asList(
                        new IndexRange(0, 1),
                        new IndexRange(2, 3),
                        new IndexRange(4, 6),
                        new IndexRange(7, 8),
                        new IndexRange(9, 9));
        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo1.getResultId()),
                subpartitionRanges);
        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo2.getResultId()),
                subpartitionRanges);
    }

    @Test
    void testAllEdgesAllToAllAndDecidedParallelismIsMaxParallelism() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(1, 2, 10L);

        AllToAllBlockingResultInfo resultInfo =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Collections.singletonList(resultInfo), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(2);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(1);
        checkAllToAllJobVertexInputInfo(
                Iterables.getOnlyElement(
                        parallelismAndInputInfos.getJobVertexInputInfos().values()),
                Arrays.asList(new IndexRange(0, 5), new IndexRange(6, 9)));
    }

    @Test
    void testAllEdgesAllToAllAndDecidedParallelismIsMinParallelism() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(4, 10, 1000L);

        AllToAllBlockingResultInfo resultInfo =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Collections.singletonList(resultInfo), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(4);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(1);
        checkAllToAllJobVertexInputInfo(
                Iterables.getOnlyElement(
                        parallelismAndInputInfos.getJobVertexInputInfos().values()),
                Arrays.asList(
                        new IndexRange(0, 1),
                        new IndexRange(2, 5),
                        new IndexRange(6, 7),
                        new IndexRange(8, 9)));
    }

    @Test
    void testFallBackToEvenlyDistributeSubpartitions() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(8, 8, 10L);

        AllToAllBlockingResultInfo resultInfo =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 1L, 10L, 1L, 10L, 1L, 10L, 1L, 10L, 1L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Collections.singletonList(resultInfo), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(8);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(1);
        checkAllToAllJobVertexInputInfo(
                Iterables.getOnlyElement(
                        parallelismAndInputInfos.getJobVertexInputInfos().values()),
                Arrays.asList(
                        new IndexRange(0, 0),
                        new IndexRange(1, 1),
                        new IndexRange(2, 2),
                        new IndexRange(3, 4),
                        new IndexRange(5, 5),
                        new IndexRange(6, 6),
                        new IndexRange(7, 7),
                        new IndexRange(8, 9)));
    }

    @Test
    void testAllEdgesAllToAllAndOneIsBroadcast() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(1, 10, 60L);

        AllToAllBlockingResultInfo resultInfo1 =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        AllToAllBlockingResultInfo resultInfo2 =
                createAllToAllBlockingResultInfo(new long[] {10L}, true);

        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(3);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(2);

        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo1.getResultId()),
                Arrays.asList(new IndexRange(0, 3), new IndexRange(4, 7), new IndexRange(8, 9)));
        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo2.getResultId()),
                Arrays.asList(new IndexRange(0, 0), new IndexRange(0, 0), new IndexRange(0, 0)));
    }

    @Test
    void testAllEdgesBroadcast() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(1, 10, 60L);

        AllToAllBlockingResultInfo resultInfo1 =
                createAllToAllBlockingResultInfo(new long[] {10L}, true);
        AllToAllBlockingResultInfo resultInfo2 =
                createAllToAllBlockingResultInfo(new long[] {10L}, true);
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(1);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(2);

        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo1.getResultId()),
                Collections.singletonList(new IndexRange(0, 0)));
        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo2.getResultId()),
                Collections.singletonList(new IndexRange(0, 0)));
    }

    @Test
    void testHavePointwiseEdges() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider(1, 10, 60L);

        AllToAllBlockingResultInfo resultInfo1 =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        PointwiseBlockingResultInfo resultInfo2 =
                createPointwiseBlockingResultInfo(
                        new long[] {8L, 12L, 21L, 9L, 13L}, new long[] {7L, 19L, 13L, 14L, 5L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Arrays.asList(resultInfo1, resultInfo2), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(4);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(2);

        checkAllToAllJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo1.getResultId()),
                Arrays.asList(
                        new IndexRange(0, 1),
                        new IndexRange(2, 4),
                        new IndexRange(5, 6),
                        new IndexRange(7, 9)));
        checkPointwiseJobVertexInputInfo(
                parallelismAndInputInfos.getJobVertexInputInfos().get(resultInfo2.getResultId()),
                Arrays.asList(
                        new IndexRange(0, 0),
                        new IndexRange(0, 0),
                        new IndexRange(1, 1),
                        new IndexRange(1, 1)),
                Arrays.asList(
                        new IndexRange(0, 1),
                        new IndexRange(2, 4),
                        new IndexRange(0, 1),
                        new IndexRange(2, 4)));
    }

    @Test
    void testParallelismAlreadyDecided() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        AllToAllBlockingResultInfo allToAllBlockingResultInfo =
                createAllToAllBlockingResultInfo(
                        new long[] {10L, 15L, 13L, 12L, 1L, 10L, 8L, 20L, 12L, 17L});
        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(),
                        Collections.singletonList(allToAllBlockingResultInfo),
                        3);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(3);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).hasSize(1);

        checkAllToAllJobVertexInputInfo(
                Iterables.getOnlyElement(
                        parallelismAndInputInfos.getJobVertexInputInfos().values()),
                Arrays.asList(new IndexRange(0, 2), new IndexRange(3, 5), new IndexRange(6, 9)));
    }

    @Test
    void testSourceJobVertex() {
        final DefaultVertexParallelismAndInputInfosDecider decider =
                createDefaultVertexParallelismAndInputInfosDecider();

        ParallelismAndInputInfos parallelismAndInputInfos =
                decider.decideParallelismAndInputInfosForVertex(
                        new JobVertexID(), Collections.emptyList(), -1);

        assertThat(parallelismAndInputInfos.getParallelism()).isEqualTo(DEFAULT_SOURCE_PARALLELISM);
        assertThat(parallelismAndInputInfos.getJobVertexInputInfos()).isEmpty();
    }

    private static void checkAllToAllJobVertexInputInfo(
            JobVertexInputInfo jobVertexInputInfo, List<IndexRange> subpartitionRange) {
        List<ExecutionVertexInputInfo> executionVertexInputInfos = new ArrayList<>();
        for (int i = 0; i < subpartitionRange.size(); ++i) {
            executionVertexInputInfos.add(
                    new ExecutionVertexInputInfo(
                            i, new IndexRange(0, 0), subpartitionRange.get(i)));
        }
        assertThat(jobVertexInputInfo.getExecutionVertexInputInfos())
                .containsExactlyInAnyOrderElementsOf(executionVertexInputInfos);
    }

    private static void checkPointwiseJobVertexInputInfo(
            JobVertexInputInfo jobVertexInputInfo,
            List<IndexRange> partitionRanges,
            List<IndexRange> subpartitionRanges) {
        assertThat(partitionRanges.size()).isEqualTo(subpartitionRanges.size());
        List<ExecutionVertexInputInfo> executionVertexInputInfos = new ArrayList<>();
        for (int i = 0; i < subpartitionRanges.size(); ++i) {
            executionVertexInputInfos.add(
                    new ExecutionVertexInputInfo(
                            i, partitionRanges.get(i), subpartitionRanges.get(i)));
        }
        assertThat(jobVertexInputInfo.getExecutionVertexInputInfos())
                .containsExactlyInAnyOrderElementsOf(executionVertexInputInfos);
    }

    private static DefaultVertexParallelismAndInputInfosDecider
            createDefaultVertexParallelismAndInputInfosDecider() {
        return createDefaultVertexParallelismAndInputInfosDecider(
                MIN_PARALLELISM, MAX_PARALLELISM, DATA_VOLUME_PER_TASK);
    }

    static DefaultVertexParallelismAndInputInfosDecider
            createDefaultVertexParallelismAndInputInfosDecider(
                    int minParallelism, int maxParallelism, long dataVolumePerTask) {
        Configuration configuration = new Configuration();

        configuration.setInteger(
                JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MAX_PARALLELISM, maxParallelism);
        configuration.setInteger(
                JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_MIN_PARALLELISM, minParallelism);
        configuration.set(
                JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_AVG_DATA_VOLUME_PER_TASK,
                new MemorySize(dataVolumePerTask));
        configuration.setInteger(
                JobManagerOptions.ADAPTIVE_BATCH_SCHEDULER_DEFAULT_SOURCE_PARALLELISM,
                DEFAULT_SOURCE_PARALLELISM);

        return DefaultVertexParallelismAndInputInfosDecider.from(configuration);
    }

    private AllToAllBlockingResultInfo createAllToAllBlockingResultInfo(
            long[] aggregatedSubpartitionBytes) {
        return createAllToAllBlockingResultInfo(aggregatedSubpartitionBytes, false);
    }

    private AllToAllBlockingResultInfo createAllToAllBlockingResultInfo(
            long[] aggregatedSubpartitionBytes, boolean isBroadcast) {
        // For simplicity, we configure only one partition here, so the aggregatedSubpartitionBytes
        // is equivalent to the subpartition bytes of partition0
        AllToAllBlockingResultInfo resultInfo =
                new AllToAllBlockingResultInfo(
                        new IntermediateDataSetID(),
                        1,
                        aggregatedSubpartitionBytes.length,
                        isBroadcast);
        resultInfo.recordPartitionInfo(0, new ResultPartitionBytes(aggregatedSubpartitionBytes));
        return resultInfo;
    }

    private PointwiseBlockingResultInfo createPointwiseBlockingResultInfo(
            long[]... subpartitionBytesByPartition) {

        final Set<Integer> subpartitionNumSet =
                Arrays.stream(subpartitionBytesByPartition)
                        .map(array -> array.length)
                        .collect(Collectors.toSet());
        // all partitions have the same subpartition num
        checkState(subpartitionNumSet.size() == 1);
        int numSubpartitions = subpartitionNumSet.iterator().next();
        int numPartitions = subpartitionBytesByPartition.length;

        PointwiseBlockingResultInfo resultInfo =
                new PointwiseBlockingResultInfo(
                        new IntermediateDataSetID(), numPartitions, numSubpartitions);

        int partitionIndex = 0;
        for (long[] subpartitionBytes : subpartitionBytesByPartition) {
            resultInfo.recordPartitionInfo(
                    partitionIndex++, new ResultPartitionBytes(subpartitionBytes));
        }

        return resultInfo;
    }

    private static class TestingBlockingResultInfo implements BlockingResultInfo {

        private final boolean isBroadcast;

        private final long producedBytes;

        private TestingBlockingResultInfo(boolean isBroadcast, long producedBytes) {
            this.isBroadcast = isBroadcast;
            this.producedBytes = producedBytes;
        }

        @Override
        public IntermediateDataSetID getResultId() {
            return new IntermediateDataSetID();
        }

        @Override
        public boolean isBroadcast() {
            return isBroadcast;
        }

        @Override
        public boolean isPointwise() {
            return false;
        }

        @Override
        public int getNumPartitions() {
            return 0;
        }

        @Override
        public int getNumSubpartitions(int partitionIndex) {
            return 0;
        }

        @Override
        public long getNumBytesProduced() {
            return producedBytes;
        }

        @Override
        public void recordPartitionInfo(int partitionIndex, ResultPartitionBytes partitionBytes) {}

        @Override
        public void resetPartitionInfo(int partitionIndex) {}
    }

    private static BlockingResultInfo createFromBroadcastResult(long producedBytes) {
        return new TestingBlockingResultInfo(true, producedBytes);
    }

    private static BlockingResultInfo createFromNonBroadcastResult(long producedBytes) {
        return new TestingBlockingResultInfo(false, producedBytes);
    }
}
