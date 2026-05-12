package com.zephyr.watch.flink.process;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.entity.SensorReading;
import org.apache.flink.api.common.functions.MapFunction;

public class JsonToSensorReadingMapFunction implements MapFunction<String, SensorReading> {

    @Override
    public SensorReading map(String value) {
        try {
            return JSON.parseObject(value, SensorReading.class);
        } catch (Exception e) {
            System.err.println("Failed to parse sensor JSON, skipping raw value: " + value);
            return null;
        }
    }
}
