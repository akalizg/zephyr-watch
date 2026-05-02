package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.entity.RulPrediction;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

public class RulRedisMapper implements RedisMapper<RulPrediction> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        return new RedisCommandDescription(RedisCommand.HSET, "watch:rul");
    }

    @Override
    public String getKeyFromData(RulPrediction data) {
        return String.valueOf(data.getMachineId());
    }

    @Override
    public String getValueFromData(RulPrediction data) {
        return String.valueOf(data.getRul());
    }
}
