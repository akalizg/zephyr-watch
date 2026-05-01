package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.RiskPrediction;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class MySqlSinkFactory {

    private MySqlSinkFactory() {
    }

    public static SinkFunction<RiskPrediction> buildRiskPredictionSink() {
        String sql = "INSERT INTO device_risk_prediction "
                + "(prediction_id, machine_id, window_start, window_end, cycle_start, cycle_end, rul, "
                + "risk_probability, risk_label, risk_level, model_version, event_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE rul=VALUES(rul), risk_probability=VALUES(risk_probability), "
                + "risk_label=VALUES(risk_label), risk_level=VALUES(risk_level), model_version=VALUES(model_version), "
                + "event_time=VALUES(event_time)";

        return JdbcSink.sink(sql, MySqlSinkFactory::bindRiskPrediction, executionOptions(), connectionOptions());
    }

    public static SinkFunction<AlertEvent> buildAlertEventSink() {
        String sql = "INSERT INTO alert_event "
                + "(alert_id, machine_id, event_time, risk_probability, rul, risk_level, alert_type, "
                + "message, source, status, model_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE risk_probability=VALUES(risk_probability), rul=VALUES(rul), "
                + "risk_level=VALUES(risk_level), message=VALUES(message), status=VALUES(status)";

        return JdbcSink.sink(sql, MySqlSinkFactory::bindAlertEvent, executionOptions(), connectionOptions());
    }

    private static void bindRiskPrediction(PreparedStatement ps, RiskPrediction value) throws SQLException {
        ps.setString(1, value.getPredictionId());
        ps.setInt(2, value.getMachineId());
        ps.setLong(3, value.getWindowStart());
        ps.setLong(4, value.getWindowEnd());
        ps.setInt(5, value.getCycleStart());
        ps.setInt(6, value.getCycleEnd());
        ps.setDouble(7, value.getRul());
        ps.setDouble(8, value.getRiskProbability());
        ps.setInt(9, value.getRiskLabel());
        ps.setString(10, value.getRiskLevel());
        ps.setString(11, value.getModelVersion());
        ps.setLong(12, value.getEventTime());
    }

    private static void bindAlertEvent(PreparedStatement ps, AlertEvent value) throws SQLException {
        ps.setString(1, value.getAlertId());
        ps.setInt(2, value.getMachineId());
        ps.setLong(3, value.getEventTime());
        ps.setDouble(4, value.getRiskProbability());
        ps.setDouble(5, value.getRul());
        ps.setString(6, value.getRiskLevel());
        ps.setString(7, value.getAlertType());
        ps.setString(8, value.getMessage());
        ps.setString(9, value.getSource());
        ps.setString(10, value.getStatus());
        ps.setString(11, value.getModelVersion());
    }

    private static JdbcExecutionOptions executionOptions() {
        return JdbcExecutionOptions.builder()
                .withBatchSize(200)
                .withBatchIntervalMs(1000)
                .withMaxRetries(3)
                .build();
    }

    private static JdbcConnectionOptions connectionOptions() {
        return new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(StorageConfig.MYSQL_URL)
                .withDriverName("com.mysql.jdbc.Driver")
                .withUsername(StorageConfig.MYSQL_USER)
                .withPassword(StorageConfig.MYSQL_PASSWORD)
                .build();
    }
}

