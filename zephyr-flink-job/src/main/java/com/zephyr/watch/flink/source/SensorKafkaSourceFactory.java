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
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.INPUT_TOPIC)
                .setGroupId(KafkaConfig.ONLINE_INFERENCE_GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
    }
}

