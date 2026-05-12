package com.zephyr.watch.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class RecommendationRepository {

    private final JdbcTemplate jdbcTemplate;

    public RecommendationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findRecommendations(Integer machineId, String alertId, int limit) {
        if (alertId != null && !alertId.trim().isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM maintenance_recommendation WHERE alert_id = ? ORDER BY created_at DESC LIMIT ?",
                    alertId,
                    limit
            );
        }
        if (machineId != null) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM maintenance_recommendation WHERE machine_id = ? ORDER BY created_at DESC LIMIT ?",
                    machineId,
                    limit
            );
        }
        return jdbcTemplate.queryForList(
                "SELECT * FROM maintenance_recommendation ORDER BY created_at DESC LIMIT ?",
                limit
        );
    }
}
