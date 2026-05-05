package com.zephyr.watch.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbcTemplate;

    public DashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("predictionCount", count("SELECT COUNT(*) FROM device_risk_prediction"));
        result.put("highRiskMachineCount", count("SELECT COUNT(DISTINCT machine_id) FROM device_risk_prediction WHERE risk_label = 1 OR risk_level IN ('HIGH','CRITICAL')"));
        result.put("alertCount", count("SELECT COUNT(*) FROM alert_event"));
        result.put("recommendationCount", count("SELECT COUNT(*) FROM maintenance_recommendation"));
        result.put("feedbackCount", count("SELECT COUNT(*) FROM review_label_feedback"));
        return result;
    }

    public List<Map<String, Object>> riskLevelDistribution() {
        return jdbcTemplate.queryForList(
                "SELECT risk_level AS riskLevel, COUNT(*) AS total "
                        + "FROM device_risk_prediction GROUP BY risk_level ORDER BY total DESC"
        );
    }

    public List<Map<String, Object>> alertTypeDistribution() {
        return jdbcTemplate.queryForList(
                "SELECT alert_type AS alertType, COUNT(*) AS total "
                        + "FROM alert_event GROUP BY alert_type ORDER BY total DESC"
        );
    }

    public List<Map<String, Object>> topRiskMachines(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT r.machine_id AS machineId, MAX(r.risk_probability) AS maxRiskProbability, "
                        + "(SELECT latest.risk_level FROM device_risk_prediction latest "
                        + "WHERE latest.machine_id = r.machine_id ORDER BY latest.window_end DESC LIMIT 1) AS latestRiskLevel "
                        + "FROM device_risk_prediction r GROUP BY r.machine_id "
                        + "ORDER BY maxRiskProbability DESC LIMIT ?",
                limit
        );
    }

    public List<Map<String, Object>> latestPredictions(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT prediction_id AS predictionId, machine_id AS machineId, window_start AS windowStart, "
                        + "window_end AS windowEnd, rul, risk_probability AS riskProbability, risk_label AS riskLabel, "
                        + "risk_level AS riskLevel, model_version AS modelVersion, created_at AS createdAt "
                        + "FROM device_risk_prediction ORDER BY window_end DESC LIMIT ?",
                limit
        );
    }

    public List<Map<String, Object>> latestAlerts(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT alert_id AS alertId, machine_id AS machineId, event_time AS eventTime, "
                        + "risk_probability AS riskProbability, rul, risk_level AS riskLevel, alert_type AS alertType, "
                        + "message, source, status, model_version AS modelVersion, created_at AS createdAt "
                        + "FROM alert_event ORDER BY event_time DESC LIMIT ?",
                limit
        );
    }

    public List<Map<String, Object>> latestRecommendations(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT recommendation_id AS recommendationId, alert_id AS alertId, machine_id AS machineId, "
                        + "action, spare_parts AS spareParts, work_order_priority AS workOrderPriority, "
                        + "similar_case_id AS similarCaseId, score, created_at AS createdAt "
                        + "FROM maintenance_recommendation ORDER BY created_at DESC LIMIT ?",
                limit
        );
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
