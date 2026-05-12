package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.BasePathBucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;

import java.time.Duration;

public final class HdfsCsvSinkFactory {

    private HdfsCsvSinkFactory() {
    }

    public static FileSink<String> buildDwdSensorCleanSink() {
        return build(StorageConfig.DWD_SENSOR_CLEAN_PATH);
    }

    public static FileSink<String> buildDwsFeatureSink() {
        return build(StorageConfig.DWS_DEVICE_FEATURE_PATH);
    }

    private static FileSink<String> build(String path) {
        return FileSink
                .forRowFormat(
                        new Path(path),
                        new SimpleStringEncoder<String>(StorageConfig.OUTPUT_CHARSET)
                )
                .withBucketAssigner(new BasePathBucketAssigner<String>())
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withRolloverInterval(Duration.ofMinutes(1))
                                .withInactivityInterval(Duration.ofSeconds(30))
                                .build()
                )
                .build();
    }
}
