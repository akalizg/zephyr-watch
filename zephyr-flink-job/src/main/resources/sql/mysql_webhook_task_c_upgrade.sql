-- Task C: run once against an existing zephyr_watch DB that was created before webhook_sign_secret / webhook_send_log.
-- Duplicate-column / duplicate-object errors mean the migration was already applied.
-- Or: mysql -u... -p... zephyr_watch < mysql_webhook_task_c_upgrade.sql

USE zephyr_watch;

ALTER TABLE webhook_config
    ADD COLUMN webhook_sign_secret VARCHAR(512) NULL COMMENT 'WeCom/DingTalk 加签密钥' AFTER webhook_url;

CREATE TABLE IF NOT EXISTS webhook_send_log (
    log_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    webhook_id BIGINT NULL,
    webhook_type VARCHAR(32) NOT NULL,
    source VARCHAR(16) NOT NULL,
    event_id VARCHAR(128) NULL,
    machine_id VARCHAR(64) NULL,
    risk_level VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    http_status INT NULL,
    attempts INT NOT NULL DEFAULT 1,
    error_message VARCHAR(1024) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_webhook_send_log_wh_created (webhook_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
