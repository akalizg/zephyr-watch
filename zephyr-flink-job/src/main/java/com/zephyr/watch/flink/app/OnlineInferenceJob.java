package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RiskPrediction;
import com.zephyr.watch.common.entity.RulPrediction;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.common.utils.JsonUtils;
import com.zephyr.watch.flink.process.CepConsecutiveRiskAlertSelector;
import com.zephyr.watch.flink.process.FeatureAnomalyAlertFunction;
import com.zephyr.watch.flink.process.FeatureWindowProcessFunction;
import com.zephyr.watch.flink.process.FlinkRuntimeConfigurer;
import com.zephyr.watch.flink.process.ParseAndValidateSensorProcessFunction;
import com.zephyr.watch.flink.process.ProcessingTimeFeatureProcessFunction;
import com.zephyr.watch.flink.process.RestRiskPredictFunction;
import com.zephyr.watch.flink.process.RiskThresholdAlertFunction;
import com.zephyr.watch.flink.process.RulPredictFunction;
import com.zephyr.watch.flink.sink.KafkaJsonSinkFactory;
import com.zephyr.watch.flink.sink.MySqlSinkFactory;
import com.zephyr.watch.flink.sink.RiskRedisMapper;
import com.zephyr.watch.flink.sink.RulRedisMapper;
import com.zephyr.watch.flink.sink.WebhookAlertSink;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import java.time.Duration;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.ProcessingTimeTrigger;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;

public class OnlineInferenceJob {

    public static void main(String[] args) throws Exception {
        String pmmlPath = args.length > 0 ? args[0] : JobConfig.DEFAULT_PMML_MODEL_PATH;
        String modelVersion = args.length > 1 ? args[1] : JobConfig.DEFAULT_MODEL_VERSION;
        boolean debugPrint = true;

        String modelServiceUrl = args.length > 3
                ? args[3]
                : System.getenv().getOrDefault("ZEPHYR_MODEL_SERVICE_URL", "http://localhost:5001/api/risk/score");
        boolean eventTimeWindows = useEventTimeWindows();
        boolean tumblingProcessingWindow = useTumblingProcessingWindow();
        int featureWindowSeconds = envInt("ZEPHYR_FEATURE_WINDOW_SECONDS", JobConfig.FEATURE_WINDOW_SECONDS);
        int featureWindowSlideSeconds = envInt("ZEPHYR_FEATURE_WINDOW_SLIDE_SECONDS", JobConfig.FEATURE_WINDOW_SLIDE_SECONDS);
        boolean featureOnlyDebug = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("ZEPHYR_FEATURE_ONLY_DEBUG", "false")
        );
        boolean enableLocalRul = envBool("ZEPHYR_ENABLE_LOCAL_RUL", true);
        boolean enableFeatureSnapshotSink = envBool("ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK", true);
        boolean enableRedisSink = envBool("ZEPHYR_ENABLE_REDIS_SINK", true);
        boolean enableKafkaOutputSink = envBool("ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK", true);
        boolean enableAlertPipeline = envBool("ZEPHYR_ENABLE_ALERT_PIPELINE", true);

        System.out.println("ZEPHYR_ONLINE_WINDOW_TIME_MODE="
                + (eventTimeWindows ? "event" : "processing")
                + ", featureWindowSeconds=" + featureWindowSeconds
                + ", featureWindowSlideSeconds=" + featureWindowSlideSeconds
                + ", tumblingProcessingWindow=" + tumblingProcessingWindow
                + ", featureOnlyDebug=" + featureOnlyDebug
                + ", enableLocalRul=" + enableLocalRul
                + ", enableFeatureSnapshotSink=" + enableFeatureSnapshotSink
                + ", enableRedisSink=" + enableRedisSink
                + ", enableKafkaOutputSink=" + enableKafkaOutputSink
                + ", enableAlertPipeline=" + enableAlertPipeline);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        FlinkRuntimeConfigurer.configureReliableStreaming(env);
        // Keep debug/verification operators from being blocked by slow external sink initialization.
        env.disableOperatorChaining();

        DataStream<String> kafkaRawStream = env.fromSource(
                SensorKafkaSourceFactory.buildOnlineInferenceSource(),
                WatermarkStrategy.noWatermarks(),
                "Kafka_Sensor_Source"
        );

        if (debugPrint) {
            kafkaRawStream.print("STEP1-KAFKA-RAW");
        }

        SingleOutputStreamOperator<SensorReading> cleanStream = kafkaRawStream
                .process(new ParseAndValidateSensorProcessFunction())
                .name("Parse_And_Validate_Sensor")
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<SensorReading>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                                .withTimestampAssigner((event, timestamp) -> event.getEventTime())
                                .withIdleness(Duration.ofMinutes(1))
                );

        if (debugPrint) {
            cleanStream.print("STEP2-CLEAN-DATA");
        }

        SingleOutputStreamOperator<SensorReading> windowInputStream = cleanStream
                .filter(new ValidSensorReadingFilter())
                .name("Filter_Valid_Window_Input");

        if (debugPrint) {
            windowInputStream.print("STEP2B-WINDOW-INPUT");
        }

        SingleOutputStreamOperator<FeatureVector> featureStream;
        if (eventTimeWindows) {
            featureStream = windowInputStream
                    .keyBy(SensorReading::getMachineId)
                    .window(SlidingEventTimeWindows.of(
                            Time.seconds(featureWindowSeconds),
                            Time.seconds(featureWindowSlideSeconds)
                    ))
                    .process(new FeatureWindowProcessFunction())
                    .name("Sliding_Event_Time_Window_Feature_Engineering");
        } else {
            featureStream = windowInputStream
                    .keyBy(SensorReading::getMachineId)
                    .process(new ProcessingTimeFeatureProcessFunction(
                            featureWindowSeconds,
                            featureWindowSlideSeconds
                    ))
                    .name("Processing_Time_Feature_Engineering");
        }

        if (debugPrint) {
            featureStream.print("STEP3-FEATURE-VECTOR");
        }

        if (featureOnlyDebug) {
            env.execute(JobConfig.ONLINE_INFERENCE_JOB_NAME + " Feature Debug");
            return;
        }

        if (enableFeatureSnapshotSink) {
            featureStream.addSink(MySqlSinkFactory.buildFeatureSnapshotSink()).name("MySQL_Feature_Snapshot_Sink");
        }

        SingleOutputStreamOperator<RiskPrediction> riskStream = featureStream
                .map(new RestRiskPredictFunction(modelServiceUrl, modelVersion))
                .name("REST_Risk_Classification_Inference");

        SingleOutputStreamOperator<RulPrediction> rulStream = null;
        if (enableLocalRul) {
            rulStream = featureStream
                    .map(new RulPredictFunction(pmmlPath))
                    .name("RUL_Remaining_Life_Inference");
        }

        if (debugPrint) {
            riskStream.print("STEP4-RISK-RESULT");
            if (rulStream != null) {
                rulStream.print("STEP5-RUL-RESULT");
            }
        }

        if (enableRedisSink) {
            FlinkJedisPoolConfig redisConfig = new FlinkJedisPoolConfig.Builder()
                    .setHost(StorageConfig.REDIS_HOST)
                    .setPort(StorageConfig.REDIS_PORT)
                    .build();

            riskStream.addSink(new RedisSink<>(redisConfig, new RiskRedisMapper())).name("Redis_Risk_Sink");
            if (rulStream != null) {
                rulStream.addSink(new RedisSink<>(redisConfig, new RulRedisMapper())).name("Redis_Rul_Sink");
            }
        }

        riskStream.addSink(MySqlSinkFactory.buildRiskPredictionSink()).name("MySQL_Risk_Prediction_Sink");

        if (enableKafkaOutputSink) {
            riskStream
                    .map(new MapFunction<RiskPrediction, String>() {
                        @Override
                        public String map(RiskPrediction value) {
                            return JsonUtils.toJsonString(value);
                        }
                    })
                    .sinkTo(KafkaJsonSinkFactory.buildRiskPredictionSink())
                    .name("Kafka_Risk_Prediction_Sink");
        }

        if (enableAlertPipeline) {
            SingleOutputStreamOperator<AlertEvent> thresholdAlertStream = riskStream
                    .flatMap(new RiskThresholdAlertFunction())
                    .name("Risk_Threshold_Alert");

            SingleOutputStreamOperator<AlertEvent> featureAlertStream = riskStream
                    .flatMap(new FeatureAnomalyAlertFunction())
                    .name("Feature_Anomaly_Alert");

            Pattern<RiskPrediction, ?> consecutiveRiskPattern = Pattern
                    .<RiskPrediction>begin("first")
                    .where(new SimpleCondition<RiskPrediction>() {
                        @Override
                        public boolean filter(RiskPrediction value) {
                            return value.getRiskLabel() != null && value.getRiskLabel() == 1;
                        }
                    })
                    .next("second")
                    .where(new SimpleCondition<RiskPrediction>() {
                        @Override
                        public boolean filter(RiskPrediction value) {
                            return value.getRiskLabel() != null && value.getRiskLabel() == 1;
                        }
                    })
                    .within(Time.minutes(2));

            SingleOutputStreamOperator<AlertEvent> cepAlertStream = CEP
                    .pattern(riskStream.keyBy(RiskPrediction::getMachineId), consecutiveRiskPattern)
                    .select(new CepConsecutiveRiskAlertSelector())
                    .name("CEP_High_Risk_Alert");

            DataStream<AlertEvent> alertStream = thresholdAlertStream
                    .union(featureAlertStream)
                    .union(cepAlertStream);

            if (debugPrint) {
                alertStream.print("STEP6-ALERT-EVENT");
            }

            alertStream.addSink(MySqlSinkFactory.buildAlertEventSink()).name("MySQL_Alert_Event_Sink");
            if (enableKafkaOutputSink) {
                alertStream
                        .map(new MapFunction<AlertEvent, String>() {
                            @Override
                            public String map(AlertEvent value) {
                                return JsonUtils.toJsonString(value);
                            }
                        })
                        .sinkTo(KafkaJsonSinkFactory.buildAlertEventSink())
                        .name("Kafka_Alert_Event_Sink");
            }
            alertStream.addSink(new WebhookAlertSink()).name("Webhook_Alert_Sink");
        }

        env.execute(JobConfig.ONLINE_INFERENCE_JOB_NAME);
    }

    private static boolean useEventTimeWindows() {
        String mode = System.getenv().getOrDefault("ZEPHYR_ONLINE_WINDOW_TIME_MODE", "processing");
        return "event".equalsIgnoreCase(mode) || "event-time".equalsIgnoreCase(mode);
    }

    private static boolean useTumblingProcessingWindow() {
        String mode = System.getenv().getOrDefault("ZEPHYR_PROCESSING_WINDOW_KIND", "tumbling");
        return "tumbling".equalsIgnoreCase(mode);
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean envBool(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim())
                || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static class ValidSensorReadingFilter implements FilterFunction<SensorReading> {
        @Override
        public boolean filter(SensorReading value) {
            return value != null
                    && value.getMachineId() != null
                    && value.getCycle() != null
                    && value.getPressure() != null
                    && value.getTemperature() != null
                    && value.getSpeed() != null
                    && value.getEventTime() != null;
        }
    }
}
