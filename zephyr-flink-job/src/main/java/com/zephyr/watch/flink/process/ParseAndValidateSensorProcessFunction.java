package com.zephyr.watch.flink.process;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.entity.SensorReading;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

public class ParseAndValidateSensorProcessFunction extends ProcessFunction<String, SensorReading> {

    public static final OutputTag<String> INVALID_SENSOR_TAG = new OutputTag<String>("invalid-sensor") {
    };

    private final SensorValidationFilter validator = new SensorValidationFilter();

    @Override
    public void processElement(String value, Context ctx, Collector<SensorReading> out) throws Exception {
        SensorReading reading;
        try {
            reading = JSON.parseObject(value, SensorReading.class);
        } catch (Exception e) {
            ctx.output(INVALID_SENSOR_TAG, value);
            return;
        }

        if (validator.filter(reading)) {
            out.collect(reading);
        } else {
            ctx.output(INVALID_SENSOR_TAG, value);
        }
    }
}

