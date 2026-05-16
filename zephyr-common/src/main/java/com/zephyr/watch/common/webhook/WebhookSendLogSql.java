package com.zephyr.watch.common.webhook;

/**
 * Shared DDL/DML fragments for Flink job and Spring API.
 */
public final class WebhookSendLogSql {

    public static final String INSERT_SEND_LOG =
            "INSERT INTO webhook_send_log (webhook_id, webhook_type, source, event_id, machine_id, risk_level, "
                    + "status, http_status, attempts, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private WebhookSendLogSql() {
    }
}
