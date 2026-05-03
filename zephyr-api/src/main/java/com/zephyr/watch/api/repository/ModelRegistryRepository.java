package com.zephyr.watch.api.repository;

import com.zephyr.watch.common.dto.ModelRegistryRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ModelRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelRegistryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findModels(String status, int limit) {
        if (status == null || status.trim().isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM model_registry ORDER BY created_at DESC LIMIT ?",
                    limit
            );
        }
        return jdbcTemplate.queryForList(
                "SELECT * FROM model_registry WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                status,
                limit
        );
    }

    public Map<String, Object> findActiveModel() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM model_registry WHERE status = ? ORDER BY deployed_at DESC, created_at DESC LIMIT 1",
                "ACTIVE"
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int upsert(ModelRegistryRequest request) {
        return jdbcTemplate.update(
                "INSERT INTO model_registry "
                        + "(model_version, model_type, model_uri, threshold_uri, feature_columns_uri, metadata_uri, optional_pmml_uri, status, deployed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'ACTIVE' THEN CURRENT_TIMESTAMP ELSE NULL END) "
                        + "ON DUPLICATE KEY UPDATE model_type=VALUES(model_type), model_uri=VALUES(model_uri), "
                        + "threshold_uri=VALUES(threshold_uri), feature_columns_uri=VALUES(feature_columns_uri), "
                        + "metadata_uri=VALUES(metadata_uri), optional_pmml_uri=VALUES(optional_pmml_uri), status=VALUES(status), "
                        + "deployed_at=CASE WHEN VALUES(status) = 'ACTIVE' THEN CURRENT_TIMESTAMP ELSE deployed_at END",
                request.getModelVersion(),
                request.getModelType(),
                request.getModelUri(),
                request.getThresholdUri(),
                request.getFeatureColumnsUri(),
                request.getMetadataUri(),
                request.getOptionalPmmlUri(),
                request.getStatus(),
                request.getStatus()
        );
    }

    public boolean exists(String modelVersion) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM model_registry WHERE model_version = ?",
                Integer.class,
                modelVersion
        );
        return count != null && count > 0;
    }

    public int activate(String modelVersion) {
        jdbcTemplate.update("UPDATE model_registry SET status = ? WHERE status = ?", "READY", "ACTIVE");
        return jdbcTemplate.update(
                "UPDATE model_registry SET status = ?, deployed_at = CURRENT_TIMESTAMP WHERE model_version = ?",
                "ACTIVE",
                modelVersion
        );
    }
}
