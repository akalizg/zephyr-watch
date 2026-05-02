package com.zephyr.watch.api.service;

import com.zephyr.watch.api.repository.ModelRegistryRepository;
import com.zephyr.watch.common.dto.ModelRegistryRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelRegistryService {

    private final ModelRegistryRepository repository;

    public ModelRegistryService(ModelRegistryRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> models(String status, int limit) {
        return repository.findModels(status, normalizeLimit(limit));
    }

    public Map<String, Object> activeModel() {
        return repository.findActiveModel();
    }

    public Map<String, Object> register(ModelRegistryRequest request) {
        validate(request);
        if (!StringUtils.hasText(request.getStatus())) {
            request.setStatus("READY");
        }
        boolean activateAfterRegister = "ACTIVE".equalsIgnoreCase(request.getStatus());
        if (activateAfterRegister) {
            request.setStatus("READY");
        }
        int affectedRows = repository.upsert(request);
        if (activateAfterRegister) {
            affectedRows += repository.activate(request.getModelVersion());
            request.setStatus("ACTIVE");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("modelVersion", request.getModelVersion());
        result.put("status", request.getStatus());
        result.put("affectedRows", affectedRows);
        return result;
    }

    public Map<String, Object> activate(String modelVersion) {
        if (!StringUtils.hasText(modelVersion)) {
            throw new IllegalArgumentException("modelVersion is required");
        }
        if (!repository.exists(modelVersion)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("modelVersion", modelVersion);
            result.put("status", "NOT_FOUND");
            result.put("affectedRows", 0);
            return result;
        }
        int affectedRows = repository.activate(modelVersion);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("modelVersion", modelVersion);
        result.put("status", "ACTIVE");
        result.put("affectedRows", affectedRows);
        return result;
    }

    private void validate(ModelRegistryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(request.getModelVersion())) {
            throw new IllegalArgumentException("modelVersion is required");
        }
        if (!StringUtils.hasText(request.getModelType())) {
            throw new IllegalArgumentException("modelType is required");
        }
        if (!StringUtils.hasText(request.getModelUri())) {
            throw new IllegalArgumentException("modelUri is required");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }
}
