package com.zephyr.watch.sink;

import com.zephyr.watch.model.RulPrediction;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

public class RulRedisMapper implements RedisMapper<RulPrediction> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        // 使用 HSET 命令。大 Key 统一定义为 "watch:rul" (代表监控系统的剩余寿命表)
        return new RedisCommandDescription(RedisCommand.HSET, "watch:rul");
    }

    @Override
    public String getKeyFromData(RulPrediction data) {
        // HSET 里的 Field (小 Key)，这里用机器 ID，例如 "1"
        return String.valueOf(data.getMachineId());
    }

    @Override
    public String getValueFromData(RulPrediction data) {
        // HSET 里的 Value，这里存预测出来的寿命数值
        return String.valueOf(data.getRul());
    }
}