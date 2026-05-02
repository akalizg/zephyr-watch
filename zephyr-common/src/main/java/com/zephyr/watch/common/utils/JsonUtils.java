package com.zephyr.watch.common.utils;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.entity.SensorReading;

public final class JsonUtils {

    private JsonUtils() {
    }

    public static SensorReading parseSensorReading(String json) {
        return JSON.parseObject(json, SensorReading.class);
    }

    public static String toJsonString(Object obj) {
        return JSON.toJSONString(obj);
    }
}
