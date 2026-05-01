package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.entity.RulPrediction;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;

public class RulRedisMapper implements RedisMapper<RulPrediction> {

    @Override
    public RedisCommandDescription getCommandDescription() {
        // 浣跨敤 HSET 鍛戒护銆傚ぇ Key 缁熶竴瀹氫箟涓?"watch:rul" (浠ｈ〃鐩戞帶绯荤粺鐨勫墿浣欏鍛借〃)
        return new RedisCommandDescription(RedisCommand.HSET, "watch:rul");
    }

    @Override
    public String getKeyFromData(RulPrediction data) {
        // HSET 閲岀殑 Field (灏?Key)锛岃繖閲岀敤鏈哄櫒 ID锛屼緥濡?"1"
        return String.valueOf(data.getMachineId());
    }

    @Override
    public String getValueFromData(RulPrediction data) {
        // HSET 閲岀殑 Value锛岃繖閲屽瓨棰勬祴鍑烘潵鐨勫鍛芥暟鍊?
        return String.valueOf(data.getRul());
    }
}
