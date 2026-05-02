package com.zephyr.watch.api.service;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.api.repository.RecommendationRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationQueryService {

    private static final String RECOMMENDATION_REDIS_KEY = "watch:recommendation";

    private final RecommendationRepository repository;
    private final StringRedisTemplate redisTemplate;

    public RecommendationQueryService(RecommendationRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> recommendations(Integer machineId, String alertId, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (alertId != null && !alertId.trim().isEmpty()) {
            String cached = redisTemplate.<String, String>opsForHash().get(RECOMMENDATION_REDIS_KEY, alertId);
            if (cached != null && !cached.trim().isEmpty()) {
                result.put("source", "redis");
                result.put("recommendations", JSON.parseArray(cached, Map.class));
                return result;
            }
        }
        result.put("source", "mysql");
        result.put("recommendations", repository.findRecommendations(machineId, alertId, normalizeLimit(limit)));
        return result;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }
}
