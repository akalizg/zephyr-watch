package com.zephyr.watch.api.service;

import com.zephyr.watch.api.repository.AlertRepository;
import com.zephyr.watch.common.constants.KafkaConfig;
import com.zephyr.watch.common.dto.AlertReviewRequest;
import com.zephyr.watch.common.utils.JsonUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertReviewService {

    private final AlertRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AlertReviewService(AlertRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public List<Map<String, Object>> alerts(String status, int limit) {
        return repository.findAlerts(status, normalizeLimit(limit));
    }

    public Map<String, Object> review(AlertReviewRequest request) {
        validate(request);
        int insertedRows = repository.insertReview(request);
        int updatedAlerts = repository.markAlertReviewed(request.getAlertId());
        kafkaTemplate.send(KafkaConfig.REVIEW_LABEL_TOPIC, request.getAlertId(), JsonUtils.toJsonString(request));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("alertId", request.getAlertId());
        result.put("reviewLabel", request.getReviewLabel());
        result.put("insertedRows", insertedRows);
        result.put("updatedAlerts", updatedAlerts);
        result.put("feedbackTopic", KafkaConfig.REVIEW_LABEL_TOPIC);
        return result;
    }

    public List<Map<String, Object>> reviewLabels(int limit) {
        return repository.findReviewedLabels(normalizeLimit(limit));
    }

    public List<Map<String, Object>> feedbackTrainingSamples(int limit) {
        return repository.findFeedbackTrainingSamples(normalizeLimit(limit));
    }

    private void validate(AlertReviewRequest request) {
        if (request == null || !StringUtils.hasText(request.getAlertId())) {
            throw new IllegalArgumentException("alertId is required");
        }
        if (!StringUtils.hasText(request.getReviewLabel())) {
            throw new IllegalArgumentException("reviewLabel is required");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }
}
