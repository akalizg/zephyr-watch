package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.*;
import com.zephyr.watch.flink.process.*;
import com.zephyr.watch.flink.sink.*;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;

import java.time.Duration;

/**
 * 修复版在线推理作业
 * 解决了水位线推进不及时和调试信息缺失导致 Redis 为空的问题
 */
public class OnlineInferenceJob {

    public static void main(String[] args) throws Exception {
        // 1. 初始化环境与配置参数
        String pmmlPath = args.length > 0 ? args[0] : JobConfig.DEFAULT_PMML_MODEL_PATH;
        String modelVersion = args.length > 1 ? args[1] : JobConfig.DEFAULT_MODEL_VERSION;

        // 强制开启调试打印，确保能看到数据流转
        boolean debugPrint = true;

        String modelServiceUrl = args.length > 3
                ? args[3]
                : System.getenv().getOrDefault("ZEPHYR_MODEL_SERVICE_URL", "http://localhost:5001/api/risk/score");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 配置 Checkpoint 和状态后端
        FlinkRuntimeConfigurer.configureReliableStreaming(env);

        // 2. 数据接入 (Source)
        DataStream<String> kafkaRawStream = env.fromSource(
                SensorKafkaSourceFactory.buildOnlineInferenceSource(),
                WatermarkStrategy.noWatermarks(),
                "Kafka_Sensor_Source"
        );

        if (debugPrint) {
            kafkaRawStream.print("STEP1-KAFKA-RAW"); // 调试点1：看 Kafka 是否连通
        }

        // 3. 数据解析、清洗与水位线分配 (Parse & Watermark)
        SingleOutputStreamOperator<SensorReading> cleanStream = kafkaRawStream
                .process(new ParseAndValidateSensorProcessFunction())
                .name("Parse_And_Validate_Sensor")
                // 核心修复：更健壮的水位线策略
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<SensorReading>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                                .withTimestampAssigner((event, timestamp) -> event.getEventTime())
                                .withIdleness(Duration.ofMinutes(1)) // 关键：处理空闲分区
                );

        if (debugPrint) {
            cleanStream.print("STEP2-CLEAN-DATA"); // 调试点2：看 JSON 解析是否正确
        }

        // 4. 特征工程 (Sliding Window)
        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .keyBy(SensorReading::getMachineId)
                .window(SlidingEventTimeWindows.of(
                        Time.seconds(JobConfig.FEATURE_WINDOW_SECONDS),
                        Time.seconds(JobConfig.FEATURE_WINDOW_SLIDE_SECONDS)
                ))
                .process(new FeatureWindowProcessFunction())
                .name("Sliding_Window_Feature_Engineering");

        if (debugPrint) {
            featureStream.print("STEP3-FEATURE-VECTOR"); // 调试点3：看窗口是否触发
        }

        // 5. 实时推理 (Risk & RUL Prediction)
        SingleOutputStreamOperator<RiskPrediction> riskStream = featureStream
                .map(new RestRiskPredictFunction(pmmlPath, modelServiceUrl, modelVersion))
                .name("REST_Risk_Classification_Inference");

        SingleOutputStreamOperator<RulPrediction> rulStream = featureStream
                .map(new RulPredictFunction(pmmlPath))
                .name("RUL_Remaining_Life_Inference");

        if (debugPrint) {
            riskStream.print("STEP4-RISK-RESULT"); // 调试点4：看风险推理输出
            rulStream.print("STEP5-RUL-RESULT");   // 调试点5：看寿命推理输出
        }

        // 6. Redis 实时写入
        FlinkJedisPoolConfig redisConfig = new FlinkJedisPoolConfig.Builder()
                .setHost(StorageConfig.REDIS_HOST)
                .setPort(StorageConfig.REDIS_PORT)
                .build();

        riskStream.addSink(new RedisSink<>(redisConfig, new RiskRedisMapper())).name("Redis_Risk_Sink");
        rulStream.addSink(new RedisSink<>(redisConfig, new RulRedisMapper())).name("Redis_Rul_Sink");

        // 7. MySQL 分发
        riskStream.addSink(MySqlSinkFactory.buildRiskPredictionSink()).name("MySQL_Risk_Prediction_Sink");

        // 8. 复杂事件处理 (CEP) - 连续高风险告警
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

        CEP.pattern(riskStream.keyBy(RiskPrediction::getMachineId), consecutiveRiskPattern)
                .select(new CepConsecutiveRiskAlertSelector())
                .addSink(MySqlSinkFactory.buildAlertEventSink())
                .name("CEP_High_Risk_Alert_Sink");

        env.execute(JobConfig.ONLINE_INFERENCE_JOB_NAME);
    }
}