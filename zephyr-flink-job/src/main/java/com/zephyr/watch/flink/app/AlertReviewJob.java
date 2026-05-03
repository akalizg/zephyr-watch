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
        private transient PreparedStatement statement;

        @Override
        public void open(Configuration parameters) throws Exception {
            connection = DriverManager.getConnection(
                StorageConfig.MYSQL_URL,
                StorageConfig.MYSQL_USER,
                StorageConfig.MYSQL_PASSWORD
            );
            statement = connection.prepareStatement(
                "INSERT INTO review_label_feedback " +
                    "(alert_id, review_label, reviewer, review_comment, event_time) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "review_label = VALUES(review_label), " +
                    "reviewer = VALUES(reviewer), " +
                    "review_comment = VALUES(review_comment), " +
                    "event_time = VALUES(event_time)"
            );
        }

        @Override
        public void invoke(JSONObject value, Context context) throws Exception {
            statement.setString(1, value.getString("alertId"));
            statement.setString(2, value.getString("reviewLabel"));
            statement.setString(3, value.getString("reviewer"));
            statement.setString(4, value.getString("reviewComment"));
            Long eventTime = value.getLong("eventTime");
            if (eventTime == null) {
                eventTime = System.currentTimeMillis();
            }
            statement.setLong(5, eventTime);
            statement.executeUpdate();
        }

        @Override
        public void close() throws Exception {
            if (statement != null) {
                statement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
    }
}
