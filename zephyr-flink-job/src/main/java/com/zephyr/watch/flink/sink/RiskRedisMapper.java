package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.entity.RiskPrediction;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

public class RiskRedisMapper implements RedisMapper<RiskPrediction> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        return new RedisCommandDescription(RedisCommand.HSET, "watch:risk");
    }

    @Override
    public String getKeyFromData(RiskPrediction data) {
        return String.valueOf(data.getMachineId());
    }

    @Override
    public String getValueFromData(RiskPrediction data) {
        return JsonUtils.toJsonString(data);
    }
}

