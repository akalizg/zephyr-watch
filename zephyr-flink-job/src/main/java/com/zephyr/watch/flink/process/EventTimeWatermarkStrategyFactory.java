package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.SensorReading;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;

import java.time.Duration;

public final class EventTimeWatermarkStrategyFactory {

    private EventTimeWatermarkStrategyFactory() {
    }

    public static WatermarkStrategy<SensorReading> build() {
        return WatermarkStrategy
                .<SensorReading>forBoundedOutOfOrderness(
                        Duration.ofSeconds(JobConfig.WATERMARK_OUT_OF_ORDERNESS_SECONDS)
                )
                .withTimestampAssigner(new SerializableTimestampAssigner<SensorReading>() {
                    @Override
                    public long extractTimestamp(SensorReading element, long recordTimestamp) {
                        return element.getEventTime();
                    }
                })
                .withIdleness(Duration.ofSeconds(JobConfig.WATERMARK_IDLE_SECONDS));
    }
}
