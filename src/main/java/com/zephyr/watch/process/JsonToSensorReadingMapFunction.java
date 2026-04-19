package com.zephyr.watch.process;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.model.SensorReading;
import org.apache.flink.api.common.functions.MapFunction;

public class JsonToSensorReadingMapFunction implements MapFunction<String, SensorReading> {

    @Override
    public SensorReading map(String value) {
        try {
            return JSON.parseObject(value, SensorReading.class);
        } catch (Exception e) {
            System.err.println("JSON 解析失败，已跳过。原始数据：" + value);
            return null;
        }
    }
}