package com.zephyr.watch.sink;

import com.zephyr.watch.config.StorageConfig;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;

import java.time.Duration;

public final class HdfsFeatureSinkFactory {

    private HdfsFeatureSinkFactory() {
    }

    public static FileSink<String> build() {
        return FileSink
                .forRowFormat(
                        new Path(StorageConfig.HDFS_FEATURE_OUTPUT_PATH),
                        new SimpleStringEncoder<String>(StorageConfig.OUTPUT_CHARSET)
                )
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withRolloverInterval(Duration.ofMinutes(1))
                                .withInactivityInterval(Duration.ofSeconds(30))
                                .build()
                )
                .build();
    }
}