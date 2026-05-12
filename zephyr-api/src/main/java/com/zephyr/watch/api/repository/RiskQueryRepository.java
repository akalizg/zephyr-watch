package com.zephyr.watch.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class RiskQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> findLatestRisk(Integer machineId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM device_risk_prediction WHERE machine_id = ? ORDER BY window_end DESC LIMIT 1",
                machineId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findRiskHistory(Integer machineId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM device_risk_prediction WHERE machine_id = ? ORDER BY window_end DESC LIMIT ?",
                machineId,
                limit
        );
    }
}
