package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RiskPrediction;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.flink.process.CepConsecutiveRiskAlertSelector;
import com.zephyr.watch.flink.process.EventTimeWatermarkStrategyFactory;
import com.zephyr.watch.flink.process.FeatureWindowProcessFunction;
import com.zephyr.watch.flink.process.FlinkRuntimeConfigurer;
import com.zephyr.watch.flink.process.OnlineRiskPredictFunction;
import com.zephyr.watch.flink.process.ParseAndValidateSensorProcessFunction;
import com.zephyr.watch.flink.process.RiskThresholdAlertFunction;
import com.zephyr.watch.flink.sink.KafkaJsonSinkFactory;
import com.zephyr.watch.flink.sink.MySqlSinkFactory;
import com.zephyr.watch.flink.sink.RiskRedisMapper;
import com.zephyr.watch.flink.sink.WebhookAlertSink;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;

public class OnlineInferenceJob {

    public static void main(String[] args) throws Exception {
        String pmmlPath = args.length > 0 ? args[0] : JobConfig.DEFAULT_PMML_MODEL_PATH;
        String modelVersion = args.length > 1 ? args[1] : JobConfig.DEFAULT_MODEL_VERSION;
        boolean debugPrintEnabled = args.length > 2
                ? Boolean.parseBoolean(args[2])
                : JobConfig.DEFAULT_DEBUG_STREAM_PRINT_ENABLED;

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        FlinkRuntimeConfigurer.configureReliableStreaming(env);

        DataStreamSource<String> kafkaStream = env.fromSource(
                SensorKafkaSourceFactory.buildOnlineInferenceSource(),
                WatermarkStrategy.noWatermarks(),
                "Kafka_Sensor_Source"
        );

        SingleOutputStreamOperator<SensorReading> cleanStream = kafkaStream
                .process(new ParseAndValidateSensorProcessFunction())
                .name("Parse_And_Validate_Sensor");

        if (debugPrintEnabled) {
            cleanStream.print("CLEAN_SENSOR");
        }

        if (debugPrintEnabled) {
            cleanStream.getSideOutput(ParseAndValidateSensorProcessFunction.INVALID_SENSOR_TAG)
                    .print("INVALID_SENSOR");
        }

        cleanStream.getSideOutput(ParseAndValidateSensorProcessFunction.INVALID_SENSOR_TAG)
                .sinkTo(KafkaJsonSinkFactory.buildInvalidSensorSink())
                .name("Kafka_Invalid_Sensor_Sink");

        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .assignTimestampsAndWatermarks(EventTimeWatermarkStrategyFactory.build())
                .keyBy(SensorReading::getMachineId)
                .window(SlidingEventTimeWindows.of(
                        Time.seconds(JobConfig.FEATURE_WINDOW_SECONDS),
                        Time.seconds(JobConfig.FEATURE_WINDOW_SLIDE_SECONDS)
                ))
                .process(new FeatureWindowProcessFunction())
                .name("Sliding_Window_Feature_Engineering");

        if (debugPrintEnabled) {
            featureStream.print("FEATURE_VECTOR");
        }

        SingleOutputStreamOperator<RiskPrediction> riskStream = featureStream
                .map(new OnlineRiskPredictFunction(pmmlPath, modelVersion))
                .name("PMML_RUL_And_Risk_Inference");

        if (debugPrintEnabled) {
            riskStream.print("RISK_PREDICTION");
        }

        riskStream
                .map(new MapFunction<RiskPrediction, String>() {
                    @Override
                    public String map(RiskPrediction value) {
                        return JsonUtils.toJsonString(value);
                    }
                })
                .sinkTo(KafkaJsonSinkFactory.buildRiskPredictionSink())
                .name("Kafka_Risk_Prediction_Sink");

        riskStream.addSink(MySqlSinkFactory.buildRiskPredictionSink())
                .name("MySQL_Risk_Prediction_Sink");

        FlinkJedisPoolConfig redisConfig = new FlinkJedisPoolConfig.Builder()
                .setHost(StorageConfig.REDIS_HOST)
                .setPort(StorageConfig.REDIS_PORT)
                .build();
        riskStream.addSink(new RedisSink<RiskPrediction>(redisConfig, new RiskRedisMapper()))
                .name("Redis_Risk_Sink");

        DataStream<AlertEvent> thresholdAlerts = riskStream
                .flatMap(new RiskThresholdAlertFunction())
                .name("Risk_Threshold_Alert");

        Pattern<RiskPrediction, ?> consecutiveHighRisk = Pattern
                .<RiskPrediction>begin("first")
                .where(new SimpleCondition<RiskPrediction>() {
                    @Override
                    public boolean filter(RiskPrediction value) {
                        return value.getRiskProbability() >= JobConfig.RISK_ALERT_THRESHOLD;
                    }
                })
                .next("second")
                .where(new SimpleCondition<RiskPrediction>() {
                    @Override
                    public boolean filter(RiskPrediction value) {
                        return value.getRiskProbability() >= JobConfig.RISK_ALERT_THRESHOLD;
                    }
                })
                .within(Time.minutes(2));

        PatternStream<RiskPrediction> cepPattern = CEP.pattern(
                riskStream.keyBy(RiskPrediction::getMachineId),
                consecutiveHighRisk
        );

        DataStream<AlertEvent> cepAlerts = cepPattern
                .select(new CepConsecutiveRiskAlertSelector())
                .name("CEP_Consecutive_High_Risk_Alert");

        DataStream<AlertEvent> alertStream = thresholdAlerts.union(cepAlerts);

        if (debugPrintEnabled) {
            alertStream.print("ALERT_EVENT");
        }

        alertStream
                .map(new MapFunction<AlertEvent, String>() {
                    @Override
                    public String map(AlertEvent value) {
                        return JsonUtils.toJsonString(value);
                    }
                })
                .sinkTo(KafkaJsonSinkFactory.buildAlertEventSink())
                .name("Kafka_Alert_Event_Sink");

        alertStream.addSink(MySqlSinkFactory.buildAlertEventSink())
                .name("MySQL_Alert_Event_Sink");

        alertStream.addSink(new WebhookAlertSink())
                .name("Webhook_Alert_Sink");

        env.execute(JobConfig.ONLINE_INFERENCE_JOB_NAME);
    }
}

