package com.zephyr.watch.process;

import com.zephyr.watch.model.SensorReading;
import com.zephyr.watch.util.JsonUtils;
import org.apache.flink.api.common.functions.MapFunction;

public class JsonToSensorReadingMapFunction implements MapFunction<String, SensorReading> {

    @Override
    public SensorReading map(String value) {
        try {
            return JsonUtils.parseSensorReading(value);
        } catch (Exception e) {
            System.err.println("JSON 解析失败，已跳过。原始数据：" + value);
            return null;
        }
    }
}