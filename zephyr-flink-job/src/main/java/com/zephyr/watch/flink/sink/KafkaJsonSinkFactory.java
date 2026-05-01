package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.KafkaConfig;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;

public final class KafkaJsonSinkFactory {

    private KafkaJsonSinkFactory() {
    }

    public static KafkaSink<String> buildRiskPredictionSink() {
        return build(KafkaConfig.RISK_PREDICTION_TOPIC, KafkaConfig.RISK_TRANSACTIONAL_ID_PREFIX);
    }

    public static KafkaSink<String> buildAlertEventSink() {
        return build(KafkaConfig.ALERT_EVENT_TOPIC, KafkaConfig.ALERT_TRANSACTIONAL_ID_PREFIX);
    }

    public static KafkaSink<String> buildInvalidSensorSink() {
        return build(KafkaConfig.INVALID_SENSOR_TOPIC, "zephyr-invalid-sensor-tx");
    }

    private static KafkaSink<String> build(String topic, String transactionalIdPrefix) {
        return KafkaSink.<String>builder()
                .setBootstrapServers(KafkaConfig.BOOTSTRAP_SERVERS)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(topic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliverGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}

