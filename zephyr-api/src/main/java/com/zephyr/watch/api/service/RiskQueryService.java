package com.zephyr.watch.api.service;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.api.repository.RiskQueryRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskQueryService {

    private static final String RISK_REDIS_KEY = "watch:risk";

    private final RiskQueryRepository repository;
    private final StringRedisTemplate redisTemplate;

    public RiskQueryService(RiskQueryRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> latestRisk(Integer machineId) {
        String cached = redisTemplate.<String, String>opsForHash().get(RISK_REDIS_KEY, String.valueOf(machineId));
        if (cached != null && !cached.trim().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("source", "redis");
            result.put("risk", JSON.parseObject(cached, Map.class));
            return result;
        }

        Map<String, Object> row = repository.findLatestRisk(machineId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source", "mysql");
        result.put("risk", row);
        return result;
    }

    public List<Map<String, Object>> riskHistory(Integer machineId, int limit) {
        return repository.findRiskHistory(machineId, normalizeLimit(limit));
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }
}
