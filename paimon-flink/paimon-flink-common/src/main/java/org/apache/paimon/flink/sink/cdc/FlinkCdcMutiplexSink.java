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

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.flink.VersionedSerializerWrapper;
import org.apache.paimon.flink.sink.Committable;
import org.apache.paimon.flink.sink.CommittableStateManager;
import org.apache.paimon.flink.sink.CommittableTypeInfo;
import org.apache.paimon.flink.sink.Committer;
import org.apache.paimon.flink.sink.CommitterOperator;
import org.apache.paimon.flink.sink.FlinkSink;
import org.apache.paimon.flink.sink.MultiTableCommittableTypeInfo;
import org.apache.paimon.flink.sink.RestoreAndFailCommittableStateManager;
import org.apache.paimon.flink.sink.StoreMultiCommitter;
import org.apache.paimon.flink.sink.StoreSinkWrite;
import org.apache.paimon.flink.sink.StoreSinkWriteImpl;
import org.apache.paimon.flink.utils.StreamExecutionEnvironmentUtils;
import org.apache.paimon.manifest.ManifestCommittableSerializer;
import org.apache.paimon.operation.Lock;
import org.apache.paimon.utils.Preconditions;
import org.apache.paimon.utils.SerializableFunction;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;

import java.io.Serializable;
import java.util.UUID;

/**
 * A {@link FlinkSink} which accepts {@link CdcRecord} and waits for a schema change if necessary.
 */
public class FlinkCdcMutiplexSink implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String WRITER_NAME = "CDC MultiplexWriter";
    private static final String GLOBAL_COMMITTER_NAME = "Multiplex Global Committer";
    private final Lock.Factory lockFactory;
    private final boolean isOverwrite = false;
    private final Catalog catalog;

    public FlinkCdcMutiplexSink(Catalog catalog, Lock.Factory lockFactory) {
        this.catalog = catalog;
        this.lockFactory = lockFactory;
    }

    private StoreSinkWrite.Provider createWriteProvider(CheckpointConfig checkpointConfig) {
        // for now, no compaction for multiplexed sink
        return (table, commitUser, state, ioManager) ->
                new StoreSinkWriteImpl(table, commitUser, state, ioManager, isOverwrite, false);
    }

    public DataStreamSink<?> sinkFrom(DataStream<MultiplexCdcRecord> input) {
        // This commitUser is valid only for new jobs.
        // After the job starts, this commitUser will be recorded into the states of write and
        // commit operators.
        // When the job restarts, commitUser will be recovered from states and this value is
        // ignored.
        String initialCommitUser = UUID.randomUUID().toString();
        return sinkFrom(
                input,
                initialCommitUser,
                createWriteProvider(input.getExecutionEnvironment().getCheckpointConfig()));
    }

    public DataStreamSink<?> sinkFrom(
            DataStream<MultiplexCdcRecord> input,
            String commitUser,
            StoreSinkWrite.Provider sinkProvider) {
        StreamExecutionEnvironment env = input.getExecutionEnvironment();
        ReadableConfig conf = StreamExecutionEnvironmentUtils.getConfiguration(env);
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();

        boolean isStreaming =
                conf.get(ExecutionOptions.RUNTIME_MODE) == RuntimeExecutionMode.STREAMING;
        boolean streamingCheckpointEnabled =
                isStreaming && checkpointConfig.isCheckpointingEnabled();
        if (streamingCheckpointEnabled) {
            assertCheckpointConfiguration(env);
        }

        CommittableTypeInfo typeInfo = new MultiTableCommittableTypeInfo();
        SingleOutputStreamOperator<Committable> written =
                input.transform(
                                WRITER_NAME,
                                typeInfo,
                                createWriteOperator(sinkProvider, isStreaming, commitUser))
                        .setParallelism(input.getParallelism());

        SingleOutputStreamOperator<?> committed =
                written.transform(
                                GLOBAL_COMMITTER_NAME,
                                typeInfo,
                                new CommitterOperator(
                                        streamingCheckpointEnabled,
                                        commitUser,
                                        createCommitterFactory(streamingCheckpointEnabled),
                                        createCommittableStateManager()))
                        .setParallelism(1)
                        .setMaxParallelism(1);
        return committed.addSink(new DiscardingSink<>()).name("end").setParallelism(1);
    }

    private void assertCheckpointConfiguration(StreamExecutionEnvironment env) {
        Preconditions.checkArgument(
                !env.getCheckpointConfig().isUnalignedCheckpointsEnabled(),
                "Paimon sink currently does not support unaligned checkpoints. Please set "
                        + ExecutionCheckpointingOptions.ENABLE_UNALIGNED.key()
                        + " to false.");
        Preconditions.checkArgument(
                env.getCheckpointConfig().getCheckpointingMode() == CheckpointingMode.EXACTLY_ONCE,
                "Paimon sink currently only supports EXACTLY_ONCE checkpoint mode. Please set "
                        + ExecutionCheckpointingOptions.CHECKPOINTING_MODE.key()
                        + " to exactly-once");
    }

    protected OneInputStreamOperator<MultiplexCdcRecord, Committable> createWriteOperator(
            StoreSinkWrite.Provider writeProvider, boolean isStreaming, String commitUser) {
        return new CdcRecordStoreMultiWriteOperator(catalog, writeProvider, commitUser);
    }

    // Table committers are dynamically created at runtime
    protected SerializableFunction<String, Committer> createCommitterFactory(
            boolean streamingCheckpointEnabled) {
        // If checkpoint is enabled for streaming job, we have to
        // commit new files list even if they're empty.
        // Otherwise we can't tell if the commit is successful after
        // a restart.
        return user -> new StoreMultiCommitter(catalog);
    }

    protected CommittableStateManager createCommittableStateManager() {
        return new RestoreAndFailCommittableStateManager(
                () -> new VersionedSerializerWrapper<>(new ManifestCommittableSerializer()));
    }
}
