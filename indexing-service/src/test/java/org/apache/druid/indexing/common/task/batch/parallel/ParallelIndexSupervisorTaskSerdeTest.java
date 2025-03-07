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

package org.apache.druid.indexing.common.task.batch.parallel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.data.input.impl.CsvInputFormat;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.LocalInputSource;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexing.common.TestUtils;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.common.task.TuningConfigBuilder;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.granularity.UniformGranularitySpec;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ParallelIndexSupervisorTaskSerdeTest
{
  private static final ObjectMapper OBJECT_MAPPER = new TestUtils().getTestObjectMapper();
  private static final List<Interval> INTERVALS = Collections.singletonList(Intervals.of("2018/2019"));

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void serde() throws IOException
  {
    ParallelIndexSupervisorTask task = new ParallelIndexSupervisorTaskBuilder()
        .ingestionSpec(
            new ParallelIndexIngestionSpecBuilder()
                .inputIntervals(INTERVALS)
                .build()
        )
        .build();

    String json = OBJECT_MAPPER.writeValueAsString(task);
    Assert.assertEquals(task, OBJECT_MAPPER.readValue(json, Task.class));
  }

  @Test
  public void forceGuaranteedRollupWithHashPartitionsMissingNumShards()
  {
    ParallelIndexSupervisorTask task = new ParallelIndexSupervisorTaskBuilder()
        .ingestionSpec(
            new ParallelIndexIngestionSpecBuilder()
                .forceGuaranteedRollup(true)
                .partitionsSpec(new HashedPartitionsSpec(null, null, null))
                .inputIntervals(INTERVALS)
                .build()
        )
        .build();

    PartitionsSpec partitionsSpec = task.getIngestionSchema().getTuningConfig().getPartitionsSpec();
    Assert.assertTrue(partitionsSpec instanceof HashedPartitionsSpec);
  }

  @Test
  public void forceGuaranteedRollupWithHashPartitionsValid()
  {
    Integer numShards = 2;
    ParallelIndexSupervisorTask task = new ParallelIndexSupervisorTaskBuilder()
        .ingestionSpec(
            new ParallelIndexIngestionSpecBuilder()
                .forceGuaranteedRollup(true)
                .partitionsSpec(new HashedPartitionsSpec(null, numShards, null))
                .inputIntervals(INTERVALS)
                .build()
        )
        .build();

    PartitionsSpec partitionsSpec = task.getIngestionSchema().getTuningConfig().getPartitionsSpec();
    Assert.assertTrue(partitionsSpec instanceof HashedPartitionsSpec);
  }

  @Test
  public void forceGuaranteedRollupWithSingleDimPartitionsMissingDimension()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("partitionDimensions must be specified");

    new ParallelIndexSupervisorTaskBuilder()
        .ingestionSpec(
            new ParallelIndexIngestionSpecBuilder()
                .forceGuaranteedRollup(true)
                .partitionsSpec(new SingleDimensionPartitionsSpec(1, null, null, true))
                .inputIntervals(INTERVALS)
                .build()
        )
        .build();
  }

  @Test
  public void forceGuaranteedRollupWithSingleDimPartitionsValid()
  {
    ParallelIndexSupervisorTask task = new ParallelIndexSupervisorTaskBuilder()
        .ingestionSpec(
            new ParallelIndexIngestionSpecBuilder()
                .forceGuaranteedRollup(true)
                .partitionsSpec(new SingleDimensionPartitionsSpec(1, null, "a", true))
                .inputIntervals(INTERVALS)
                .build()
        )
        .build();

    PartitionsSpec partitionsSpec = task.getIngestionSchema().getTuningConfig().getPartitionsSpec();
    Assert.assertTrue(partitionsSpec instanceof SingleDimensionPartitionsSpec);
  }

  private static class ParallelIndexSupervisorTaskBuilder
  {
    private static final String ID = "taskId";
    private final TaskResource taskResource = new TaskResource("group", 1);
    private final Map<String, Object> context = Collections.emptyMap();

    private ParallelIndexIngestionSpec ingestionSpec;

    ParallelIndexSupervisorTaskBuilder ingestionSpec(ParallelIndexIngestionSpec ingestionSpec)
    {
      this.ingestionSpec = ingestionSpec;
      return this;
    }

    ParallelIndexSupervisorTask build()
    {
      return new ParallelIndexSupervisorTask(
          ID,
          null,
          taskResource,
          ingestionSpec,
          context
      );
    }
  }

  private static class ParallelIndexIngestionSpecBuilder
  {
    private static final TimestampSpec TIMESTAMP_SPEC = new TimestampSpec("ts", "auto", null);
    private static final DimensionsSpec DIMENSIONS_SPEC = new DimensionsSpec(
        DimensionsSpec.getDefaultSchemas(Arrays.asList("ts", "dim"))
    );

    private final ParallelIndexIOConfig ioConfig = new ParallelIndexIOConfig(
        new LocalInputSource(new File("tmp"), "test_*"),
        new CsvInputFormat(Arrays.asList("ts", "dim", "val"), null, null, false, 0, null),
        false,
        null
    );

    // For dataSchema.granularitySpec
    @Nullable
    private List<Interval> inputIntervals = null;

    // For tuningConfig
    @Nullable
    private Boolean forceGuaranteedRollup = null;
    @Nullable
    PartitionsSpec partitionsSpec = null;

    @SuppressWarnings("SameParameterValue")
    ParallelIndexIngestionSpecBuilder inputIntervals(List<Interval> inputIntervals)
    {
      this.inputIntervals = inputIntervals;
      return this;
    }

    @SuppressWarnings("SameParameterValue")
    ParallelIndexIngestionSpecBuilder forceGuaranteedRollup(boolean forceGuaranteedRollup)
    {
      this.forceGuaranteedRollup = forceGuaranteedRollup;
      return this;
    }

    ParallelIndexIngestionSpecBuilder partitionsSpec(PartitionsSpec partitionsSpec)
    {
      this.partitionsSpec = partitionsSpec;
      return this;
    }

    ParallelIndexIngestionSpec build()
    {
      DataSchema dataSchema = DataSchema.builder()
                                        .withDataSource("datasource")
                                        .withTimestamp(TIMESTAMP_SPEC)
                                        .withDimensions(DIMENSIONS_SPEC)
                                        .withAggregators(new LongSumAggregatorFactory("val", "val"))
                                        .withGranularity(
                                            new UniformGranularitySpec(
                                                Granularities.DAY,
                                                Granularities.MINUTE,
                                                inputIntervals
                                            )
                                        )
                                        .build();

      ParallelIndexTuningConfig tuningConfig = TuningConfigBuilder
          .forParallelIndexTask()
          .withPartitionsSpec(partitionsSpec)
          .withForceGuaranteedRollup(forceGuaranteedRollup)
          .build();
      return new ParallelIndexIngestionSpec(dataSchema, ioConfig, tuningConfig);
    }
  }
}
