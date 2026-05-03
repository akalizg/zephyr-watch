USE zephyr_watch;

ALTER TABLE device_risk_prediction
  ADD COLUMN sample_count INT,
  ADD COLUMN pressure_min DOUBLE,
  ADD COLUMN pressure_max DOUBLE,
  ADD COLUMN pressure_avg DOUBLE,
  ADD COLUMN pressure_std DOUBLE,
  ADD COLUMN pressure_trend DOUBLE,
  ADD COLUMN temperature_min DOUBLE,
  ADD COLUMN temperature_max DOUBLE,
  ADD COLUMN temperature_avg DOUBLE,
  ADD COLUMN temperature_std DOUBLE,
  ADD COLUMN temperature_trend DOUBLE,
  ADD COLUMN speed_min DOUBLE,
  ADD COLUMN speed_max DOUBLE,
  ADD COLUMN speed_avg DOUBLE,
  ADD COLUMN speed_std DOUBLE,
  ADD COLUMN speed_trend DOUBLE;

CREATE TABLE IF NOT EXISTS review_label_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id VARCHAR(128) NOT NULL,
    review_label VARCHAR(32) NOT NULL,
    reviewer VARCHAR(64),
    review_comment VARCHAR(512),
    event_time BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_review_feedback_alert (alert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS feedback_training_sample (
    sample_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id VARCHAR(128) NOT NULL,
    machine_id INT NOT NULL,
    window_start BIGINT,
    window_end BIGINT,
    sample_count INT,
    cycle_start INT,
    cycle_end INT,
    pressure_min DOUBLE,
    pressure_max DOUBLE,
    pressure_avg DOUBLE,
    pressure_std DOUBLE,
    pressure_trend DOUBLE,
    temperature_min DOUBLE,
    temperature_max DOUBLE,
    temperature_avg DOUBLE,
    temperature_std DOUBLE,
    temperature_trend DOUBLE,
    speed_min DOUBLE,
    speed_max DOUBLE,
    speed_avg DOUBLE,
    speed_std DOUBLE,
    speed_trend DOUBLE,
    risk_label TINYINT NOT NULL,
    review_label VARCHAR(32),
    reviewer VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_feedback_alert (alert_id),
    KEY idx_feedback_machine_window (machine_id, window_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS webhook_config (
    channel VARCHAR(64) NOT NULL PRIMARY KEY,
    webhook_url VARCHAR(1024) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
