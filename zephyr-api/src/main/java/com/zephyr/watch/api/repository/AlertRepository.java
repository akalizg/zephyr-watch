package com.zephyr.watch.api.repository;

import com.zephyr.watch.common.dto.AlertReviewRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class AlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findAlerts(String status, int limit) {
        if (status == null || status.trim().isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM alert_event ORDER BY event_time DESC LIMIT ?",
                    limit
            );
        }
        return jdbcTemplate.queryForList(
                "SELECT * FROM alert_event WHERE status = ? ORDER BY event_time DESC LIMIT ?",
                status,
                limit
        );
    }

    public int insertReview(AlertReviewRequest request) {
        return jdbcTemplate.update(
                "INSERT INTO alert_review (alert_id, reviewer, review_label, review_comment) VALUES (?, ?, ?, ?)",
                request.getAlertId(),
                request.getReviewer(),
                request.getReviewLabel(),
                request.getReviewComment()
        );
    }

    public int markAlertReviewed(String alertId) {
        return jdbcTemplate.update(
                "UPDATE alert_event SET status = ? WHERE alert_id = ?",
                "REVIEWED",
                alertId
        );
    }

    public List<Map<String, Object>> findReviewedLabels(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT ar.review_id, ar.alert_id, ar.reviewer, ar.review_label, ar.review_comment, ar.reviewed_at, "
                        + "ae.machine_id, ae.event_time, ae.risk_probability, ae.rul, ae.risk_level, "
                        + "ae.alert_type, ae.model_version "
                        + "FROM alert_review ar "
                        + "LEFT JOIN alert_event ae ON ar.alert_id = ae.alert_id "
                        + "ORDER BY ar.reviewed_at DESC LIMIT ?",
                limit
        );
    }

    public List<Map<String, Object>> findFeedbackTrainingSamples(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT "
                        + "machine_id AS machineId, window_start AS windowStart, window_end AS windowEnd, "
                        + "sample_count AS sampleCount, cycle_start AS cycleStart, cycle_end AS cycleEnd, "
                        + "pressure_min AS pressureMin, pressure_max AS pressureMax, pressure_avg AS pressureAvg, "
                        + "pressure_std AS pressureStd, pressure_trend AS pressureTrend, "
                        + "temperature_min AS temperatureMin, temperature_max AS temperatureMax, "
                        + "temperature_avg AS temperatureAvg, temperature_std AS temperatureStd, "
                        + "temperature_trend AS temperatureTrend, speed_min AS speedMin, speed_max AS speedMax, "
                        + "speed_avg AS speedAvg, speed_std AS speedStd, speed_trend AS speedTrend, "
                        + "rul, risk_label AS riskLabel, review_label AS reviewLabel, reviewer, alert_id AS alertId "
                        + "FROM feedback_training_sample "
                        + "WHERE sample_count IS NOT NULL "
                        + "ORDER BY created_at DESC LIMIT ?",
                limit
        );
    }
}
