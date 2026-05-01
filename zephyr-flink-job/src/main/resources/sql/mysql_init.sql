CREATE DATABASE IF NOT EXISTS zephyr_watch DEFAULT CHARACTER SET utf8mb4;
USE zephyr_watch;

CREATE TABLE IF NOT EXISTS device_risk_prediction (
    prediction_id VARCHAR(128) NOT NULL PRIMARY KEY,
    machine_id INT NOT NULL,
    window_start BIGINT NOT NULL,
    window_end BIGINT NOT NULL,
    cycle_start INT NOT NULL,
    cycle_end INT NOT NULL,
    rul DOUBLE NOT NULL,
    risk_probability DOUBLE NOT NULL,
    risk_label TINYINT NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    event_time BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_device_risk_machine_time (machine_id, window_end),
    KEY idx_device_risk_level_time (risk_level, window_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert_event (
    alert_id VARCHAR(128) NOT NULL PRIMARY KEY,
    machine_id INT NOT NULL,
    event_time BIGINT NOT NULL,
    risk_probability DOUBLE NOT NULL,
    rul DOUBLE NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    alert_type VARCHAR(64) NOT NULL,
    message VARCHAR(512) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    model_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_alert_machine_time (machine_id, event_time),
    KEY idx_alert_status_time (status, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert_review (
    review_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id VARCHAR(128) NOT NULL,
    reviewer VARCHAR(64),
    review_label VARCHAR(32) NOT NULL,
    review_comment VARCHAR(512),
    reviewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_alert_review_alert (alert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS maintenance_recommendation (
    recommendation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id VARCHAR(128) NOT NULL,
    machine_id INT NOT NULL,
    action VARCHAR(256) NOT NULL,
    spare_parts VARCHAR(256),
    work_order_priority VARCHAR(32) NOT NULL,
    similar_case_id VARCHAR(128),
    score DOUBLE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recommendation_alert (alert_id),
    KEY idx_recommendation_machine (machine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_registry (
    model_version VARCHAR(128) NOT NULL PRIMARY KEY,
    model_type VARCHAR(64) NOT NULL,
    model_uri VARCHAR(512) NOT NULL,
    threshold_uri VARCHAR(512),
    feature_columns_uri VARCHAR(512),
    metadata_uri VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    deployed_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
