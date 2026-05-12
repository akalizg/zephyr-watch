package com.zephyr.watch.flink.source;

import com.zephyr.watch.common.constants.KafkaConfig;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;

public final class SensorKafkaSourceFactory {

    private SensorKafkaSourceFactory() {
    }

    public static KafkaSource<String> build() {
        return KafkaSource.<String>builder()
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.INPUT_TOPIC)
                .setGroupId(KafkaConfig.GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
    }

    public static KafkaSource<String> buildOnlineInferenceSource() {
        return KafkaSource.<String>builder()
                // 建议：如果 node1 还是不通，这里可以临时硬编码 IP 192.168.88.161:9092 测试
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.INPUT_TOPIC)
                // 修复1：使用随机 ID 避免分区被旧任务占用
                .setGroupId(KafkaConfig.ONLINE_INFERENCE_GROUP_ID + "_" + System.currentTimeMillis())
                // 修复2：改为从最新位置消费，配合 Python 脚本实时触发
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                // 修复3：针对 Kafka 2.4.1 可能需要的属性配置
                .setProperty("partition.discovery.interval.ms", "10000")
                .build();
    }

    public static KafkaSource<String> buildAlertEventSource() {
        return KafkaSource.<String>builder()
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.ALERT_EVENT_TOPIC)
                .setGroupId(KafkaConfig.RECOMMENDATION_GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
    }

    public static KafkaSource<String> buildReviewLabelSource() {
        return KafkaSource.<String>builder()
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.REVIEW_LABEL_TOPIC)
                .setGroupId(KafkaConfig.ALERT_REVIEW_GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
    }
}

