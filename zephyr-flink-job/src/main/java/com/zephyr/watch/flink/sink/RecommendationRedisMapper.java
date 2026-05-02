package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.entity.MaintenanceRecommendation;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

import java.util.Collections;

public class RecommendationRedisMapper implements RedisMapper<MaintenanceRecommendation> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        return new RedisCommandDescription(RedisCommand.HSET, "watch:recommendation");
    }

    @Override
    public String getKeyFromData(MaintenanceRecommendation data) {
        return data.getAlertId();
    }

    @Override
    public String getValueFromData(MaintenanceRecommendation data) {
        return JsonUtils.toJsonString(Collections.singletonList(data));
    }
}
