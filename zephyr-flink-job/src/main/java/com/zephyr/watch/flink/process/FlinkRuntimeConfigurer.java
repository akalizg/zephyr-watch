package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.JobConfig;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public final class FlinkRuntimeConfigurer {

    private FlinkRuntimeConfigurer() {
    }

    public static void configureReliableStreaming(StreamExecutionEnvironment env) {
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(JobConfig.PARALLELISM);
        env.setStateBackend(new HashMapStateBackend());
        env.getCheckpointConfig().setCheckpointStorage(JobConfig.CHECKPOINT_STORAGE_PATH);
        env.enableCheckpointing(JobConfig.CHECKPOINT_INTERVAL_MS, CheckpointingMode.EXACTLY_ONCE);

        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setMinPauseBetweenCheckpoints(5_000L);
        checkpointConfig.setCheckpointTimeout(60_000L);
        checkpointConfig.setTolerableCheckpointFailureNumber(3);
        checkpointConfig.enableExternalizedCheckpoints(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION
        );
    }
}

