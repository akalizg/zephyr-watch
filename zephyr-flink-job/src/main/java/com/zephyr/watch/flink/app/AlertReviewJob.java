package com.zephyr.watch.flink.app;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.constants.KafkaConfig;
import com.zephyr.watch.common.dto.AlertReviewRequest;
import com.zephyr.watch.flink.process.FlinkRuntimeConfigurer;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class AlertReviewJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        FlinkRuntimeConfigurer.configureReliableStreaming(env);

        SingleOutputStreamOperator<AlertReviewRequest> reviewStream = env.fromSource(
                        SensorKafkaSourceFactory.buildReviewLabelSource(),
                        WatermarkStrategy.noWatermarks(),
                        "Kafka_Review_Label_Source"
                )
                .map(new MapFunction<String, AlertReviewRequest>() {
                    @Override
                    public AlertReviewRequest map(String value) {
                        return JSON.parseObject(value, AlertReviewRequest.class);
                    }
                })
                .name("Parse_Review_Label");

        reviewStream.print("REVIEW_LABEL_FEEDBACK");

        env.execute("Zephyr-Watch Review Label Feedback Job: " + KafkaConfig.REVIEW_LABEL_TOPIC);
    }
}
