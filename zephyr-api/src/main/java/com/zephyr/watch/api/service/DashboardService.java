package com.zephyr.watch.api.service;

import com.zephyr.watch.api.repository.DashboardRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> overview() {
        return repository.overview();
    }

    public List<Map<String, Object>> riskLevelDistribution() {
        return repository.riskLevelDistribution();
    }

    public List<Map<String, Object>> alertTypeDistribution() {
        return repository.alertTypeDistribution();
    }

    public List<Map<String, Object>> topRiskMachines(int limit) {
        return repository.topRiskMachines(normalizeLimit(limit));
    }

    public List<Map<String, Object>> latestPredictions(int limit) {
        return repository.latestPredictions(normalizeLimit(limit));
    }

    public List<Map<String, Object>> latestAlerts(int limit) {
        return repository.latestAlerts(normalizeLimit(limit));
    }

    public List<Map<String, Object>> latestRecommendations(int limit) {
        return repository.latestRecommendations(normalizeLimit(limit));
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
