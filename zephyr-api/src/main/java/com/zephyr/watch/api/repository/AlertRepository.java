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
}
