package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.MaintenanceRecommendation;
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
                + "risk_probability, risk_label, risk_level, model_version, event_time, sample_count, "
                + "pressure_min, pressure_max, pressure_avg, pressure_std, pressure_trend, "
                + "temperature_min, temperature_max, temperature_avg, temperature_std, temperature_trend, "
                + "speed_min, speed_max, speed_avg, speed_std, speed_trend) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE rul=VALUES(rul), risk_probability=VALUES(risk_probability), "
                + "risk_label=VALUES(risk_label), risk_level=VALUES(risk_level), model_version=VALUES(model_version), "
                + "event_time=VALUES(event_time), sample_count=VALUES(sample_count), "
                + "pressure_min=VALUES(pressure_min), pressure_max=VALUES(pressure_max), "
                + "pressure_avg=VALUES(pressure_avg), pressure_std=VALUES(pressure_std), "
                + "pressure_trend=VALUES(pressure_trend), temperature_min=VALUES(temperature_min), "
                + "temperature_max=VALUES(temperature_max), temperature_avg=VALUES(temperature_avg), "
                + "temperature_std=VALUES(temperature_std), temperature_trend=VALUES(temperature_trend), "
                + "speed_min=VALUES(speed_min), speed_max=VALUES(speed_max), speed_avg=VALUES(speed_avg), "
                + "speed_std=VALUES(speed_std), speed_trend=VALUES(speed_trend)";

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

    public static SinkFunction<FeatureVector> buildFeatureSnapshotSink() {
        String sql = "INSERT INTO online_feature_snapshot "
                + "(feature_id, machine_id, window_start, window_end, sample_count, cycle_start, cycle_end, "
                + "pressure_min, pressure_max, pressure_avg, pressure_std, pressure_trend, "
                + "temperature_min, temperature_max, temperature_avg, temperature_std, temperature_trend, "
                + "speed_min, speed_max, speed_avg, speed_std, speed_trend, event_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE sample_count=VALUES(sample_count), cycle_start=VALUES(cycle_start), "
                + "cycle_end=VALUES(cycle_end), pressure_min=VALUES(pressure_min), pressure_max=VALUES(pressure_max), "
                + "pressure_avg=VALUES(pressure_avg), pressure_std=VALUES(pressure_std), "
                + "pressure_trend=VALUES(pressure_trend), temperature_min=VALUES(temperature_min), "
                + "temperature_max=VALUES(temperature_max), temperature_avg=VALUES(temperature_avg), "
                + "temperature_std=VALUES(temperature_std), temperature_trend=VALUES(temperature_trend), "
                + "speed_min=VALUES(speed_min), speed_max=VALUES(speed_max), speed_avg=VALUES(speed_avg), "
                + "speed_std=VALUES(speed_std), speed_trend=VALUES(speed_trend), event_time=VALUES(event_time)";

        return JdbcSink.sink(sql, MySqlSinkFactory::bindFeatureSnapshot, executionOptions(), connectionOptions());
    }

    public static SinkFunction<MaintenanceRecommendation> buildRecommendationSink() {
        String sql = "INSERT INTO maintenance_recommendation "
                + "(alert_id, machine_id, action, spare_parts, work_order_priority, similar_case_id, score) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE action=VALUES(action), spare_parts=VALUES(spare_parts), "
                + "work_order_priority=VALUES(work_order_priority), score=VALUES(score)";

        return JdbcSink.sink(sql, MySqlSinkFactory::bindRecommendation, executionOptions(), connectionOptions());
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
        ps.setObject(13, value.getSampleCount());
        ps.setObject(14, value.getPressureMin());
        ps.setObject(15, value.getPressureMax());
        ps.setObject(16, value.getPressureAvg());
        ps.setObject(17, value.getPressureStd());
        ps.setObject(18, value.getPressureTrend());
        ps.setObject(19, value.getTemperatureMin());
        ps.setObject(20, value.getTemperatureMax());
        ps.setObject(21, value.getTemperatureAvg());
        ps.setObject(22, value.getTemperatureStd());
        ps.setObject(23, value.getTemperatureTrend());
        ps.setObject(24, value.getSpeedMin());
        ps.setObject(25, value.getSpeedMax());
        ps.setObject(26, value.getSpeedAvg());
        ps.setObject(27, value.getSpeedStd());
        ps.setObject(28, value.getSpeedTrend());
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

    private static void bindFeatureSnapshot(PreparedStatement ps, FeatureVector value) throws SQLException {
        ps.setString(1, value.getMachineId() + "-" + value.getWindowEnd());
        ps.setInt(2, value.getMachineId());
        ps.setLong(3, value.getWindowStart());
        ps.setLong(4, value.getWindowEnd());
        ps.setObject(5, value.getSampleCount());
        ps.setObject(6, value.getCycleStart());
        ps.setObject(7, value.getCycleEnd());
        ps.setObject(8, value.getPressureMin());
        ps.setObject(9, value.getPressureMax());
        ps.setObject(10, value.getPressureAvg());
        ps.setObject(11, value.getPressureStd());
        ps.setObject(12, value.getPressureTrend());
        ps.setObject(13, value.getTemperatureMin());
        ps.setObject(14, value.getTemperatureMax());
        ps.setObject(15, value.getTemperatureAvg());
        ps.setObject(16, value.getTemperatureStd());
        ps.setObject(17, value.getTemperatureTrend());
        ps.setObject(18, value.getSpeedMin());
        ps.setObject(19, value.getSpeedMax());
        ps.setObject(20, value.getSpeedAvg());
        ps.setObject(21, value.getSpeedStd());
        ps.setObject(22, value.getSpeedTrend());
        ps.setLong(23, System.currentTimeMillis());
    }

    private static void bindRecommendation(PreparedStatement ps, MaintenanceRecommendation value) throws SQLException {
        ps.setString(1, value.getAlertId());
        ps.setInt(2, value.getMachineId());
        ps.setString(3, value.getAction());
        ps.setString(4, value.getSpareParts());
        ps.setString(5, value.getWorkOrderPriority());
        ps.setString(6, value.getSimilarCaseId());
        ps.setDouble(7, value.getScore());
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

