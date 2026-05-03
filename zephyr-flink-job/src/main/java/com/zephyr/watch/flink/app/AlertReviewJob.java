package com.zephyr.watch.flink.app;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.flink.process.FlinkRuntimeConfigurer;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

public class AlertReviewJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        FlinkRuntimeConfigurer.configureReliableStreaming(env);

        SingleOutputStreamOperator<JSONObject> reviewStream = env
            .fromSource(
                SensorKafkaSourceFactory.buildReviewLabelSource(),
                WatermarkStrategy.noWatermarks(),
                "Kafka_Review_Label_Source"
            )
            .map(JSON::parseObject)
            .name("Parse_Review_Label");

        reviewStream
            .addSink(new ReviewLabelFeedbackSink())
            .name("MySQL_Review_Label_Feedback_Sink");

        env.execute("Zephyr-Watch Review Label Feedback Job: review_label_topic");
    }

    private static class ReviewLabelFeedbackSink extends RichSinkFunction<JSONObject> {

        private transient Connection connection;
        private transient PreparedStatement feedbackStatement;
        private transient PreparedStatement trainingSampleStatement;

        @Override
        public void open(Configuration parameters) throws Exception {
            connection = DriverManager.getConnection(
                StorageConfig.MYSQL_URL,
                StorageConfig.MYSQL_USER,
                StorageConfig.MYSQL_PASSWORD
            );
            feedbackStatement = connection.prepareStatement(
                "INSERT INTO review_label_feedback " +
                    "(alert_id, review_label, reviewer, review_comment, event_time) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "review_label = VALUES(review_label), " +
                    "reviewer = VALUES(reviewer), " +
                    "review_comment = VALUES(review_comment), " +
                    "event_time = VALUES(event_time)"
            );
            trainingSampleStatement = connection.prepareStatement(
                "INSERT INTO feedback_training_sample " +
                    "(alert_id, machine_id, window_start, window_end, sample_count, cycle_start, cycle_end, " +
                    "pressure_min, pressure_max, pressure_avg, pressure_std, pressure_trend, " +
                    "temperature_min, temperature_max, temperature_avg, temperature_std, temperature_trend, " +
                    "speed_min, speed_max, speed_avg, speed_std, speed_trend, risk_label, review_label, reviewer) " +
                    "SELECT ?, drp.machine_id, drp.window_start, drp.window_end, drp.sample_count, " +
                    "drp.cycle_start, drp.cycle_end, drp.pressure_min, drp.pressure_max, drp.pressure_avg, " +
                    "drp.pressure_std, drp.pressure_trend, drp.temperature_min, drp.temperature_max, " +
                    "drp.temperature_avg, drp.temperature_std, drp.temperature_trend, drp.speed_min, " +
                    "drp.speed_max, drp.speed_avg, drp.speed_std, drp.speed_trend, ?, ?, ? " +
                    "FROM alert_event ae " +
                    "JOIN device_risk_prediction drp ON drp.machine_id = ae.machine_id AND drp.window_end = ae.event_time " +
                    "WHERE ae.alert_id = ? " +
                    "ORDER BY drp.event_time DESC LIMIT 1 " +
                    "ON DUPLICATE KEY UPDATE " +
                    "machine_id = VALUES(machine_id), window_start = VALUES(window_start), window_end = VALUES(window_end), " +
                    "sample_count = VALUES(sample_count), cycle_start = VALUES(cycle_start), cycle_end = VALUES(cycle_end), " +
                    "pressure_min = VALUES(pressure_min), pressure_max = VALUES(pressure_max), pressure_avg = VALUES(pressure_avg), " +
                    "pressure_std = VALUES(pressure_std), pressure_trend = VALUES(pressure_trend), " +
                    "temperature_min = VALUES(temperature_min), temperature_max = VALUES(temperature_max), " +
                    "temperature_avg = VALUES(temperature_avg), temperature_std = VALUES(temperature_std), " +
                    "temperature_trend = VALUES(temperature_trend), speed_min = VALUES(speed_min), speed_max = VALUES(speed_max), " +
                    "speed_avg = VALUES(speed_avg), speed_std = VALUES(speed_std), speed_trend = VALUES(speed_trend), " +
                    "risk_label = VALUES(risk_label), review_label = VALUES(review_label), reviewer = VALUES(reviewer)"
            );
        }

        @Override
        public void invoke(JSONObject value, Context context) throws Exception {
            String alertId = value.getString("alertId");
            String reviewLabel = value.getString("reviewLabel");
            String reviewer = value.getString("reviewer");
            feedbackStatement.setString(1, alertId);
            feedbackStatement.setString(2, reviewLabel);
            feedbackStatement.setString(3, reviewer);
            feedbackStatement.setString(4, value.getString("reviewComment"));
            Long eventTime = value.getLong("eventTime");
            if (eventTime == null) {
                eventTime = System.currentTimeMillis();
            }
            feedbackStatement.setLong(5, eventTime);
            feedbackStatement.executeUpdate();

            Integer riskLabel = toRiskLabel(reviewLabel);
            if (riskLabel != null) {
                trainingSampleStatement.setString(1, alertId);
                trainingSampleStatement.setInt(2, riskLabel);
                trainingSampleStatement.setString(3, reviewLabel);
                trainingSampleStatement.setString(4, reviewer);
                trainingSampleStatement.setString(5, alertId);
                trainingSampleStatement.executeUpdate();
            }
        }

        private Integer toRiskLabel(String reviewLabel) {
            if ("TRUE_POSITIVE".equalsIgnoreCase(reviewLabel)) {
                return 1;
            }
            if ("FALSE_POSITIVE".equalsIgnoreCase(reviewLabel)) {
                return 0;
            }
            if ("1".equals(reviewLabel) || "HIGH_RISK".equalsIgnoreCase(reviewLabel)) {
                return 1;
            }
            if ("0".equals(reviewLabel) || "LOW_RISK".equalsIgnoreCase(reviewLabel)) {
                return 0;
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            if (feedbackStatement != null) {
                feedbackStatement.close();
            }
            if (trainingSampleStatement != null) {
                trainingSampleStatement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
    }
}
