package com.zephyr.watch.flink.sink;


import com.alibaba.fastjson.JSON;
import com.zephyr.watch.common.entity.RulPrediction;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 实时预测结果 Redis 映射器
 * 作用：将 RulPrediction 写入 Redis Hash 结构，供前端实时接口调用
 */
public class RulRedisMapper implements RedisMapper<RulPrediction> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        // 使用 HSET 命令，将所有机器的最新状态存储在名为 "watch:rul" 的 Hash 中
        return new RedisCommandDescription(RedisCommand.HSET, "watch:rul");
    }

    @Override
    public String getKeyFromData(RulPrediction data) {
        // 使用机器 ID 作为 Hash 内的 Field Key
        return data != null ? String.valueOf(data.getMachineId()) : "unknown";
    }

    @Override
    public String getValueFromData(RulPrediction data) {
        if (data == null) {
            return "{}";
        }

        // 构造一个包含更多信息的 Map，方便前端大屏展示
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("machineId", data.getMachineId());
        resultMap.put("rul", data.getRul());
        // 假设 RulPrediction 包含以下字段，如果没有可以根据你的实体类调整
        resultMap.put("riskLevel", data.getRiskLevel());
        resultMap.put("updateTime", System.currentTimeMillis()); // 记录预测更新时间

        // 将对象序列化为 JSON 字符串
        return JSON.toJSONString(resultMap);
    }
}