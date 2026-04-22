package com.zephyr.watch.app;

import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.model.FeatureVector;
import com.zephyr.watch.model.SensorReading;
import com.zephyr.watch.model.RulPrediction; // 引入新写的预测实体类
import com.zephyr.watch.process.RulPredictFunction; // 引入新写的预测算子
import com.zephyr.watch.process.EventTimeWatermarkStrategyFactory;
import com.zephyr.watch.process.FeatureWindowProcessFunction;
import com.zephyr.watch.process.JsonToSensorReadingMapFunction;
import com.zephyr.watch.process.SensorValidationFilter;
import com.zephyr.watch.sink.HdfsCsvSinkFactory;
import com.zephyr.watch.source.SensorKafkaSourceFactory;
import com.zephyr.watch.util.CsvUtils;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import com.zephyr.watch.sink.RulRedisMapper;
import java.net.URL;

public class OfflineWarehouseJob {

    public static void main(String[] args) throws Exception {
        System.setProperty("HADOOP_USER_NAME", "root");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(JobConfig.PARALLELISM);
        env.enableCheckpointing(JobConfig.CHECKPOINT_INTERVAL_MS);

        DataStreamSource<String> kafkaStream = env.fromSource(
                SensorKafkaSourceFactory.build(),
                WatermarkStrategy.noWatermarks(),
                "Kafka_Sensor_Source"
        );

        SingleOutputStreamOperator<SensorReading> cleanStream = kafkaStream
                .map(new JsonToSensorReadingMapFunction())
                .filter(new SensorValidationFilter())
                .assignTimestampsAndWatermarks(EventTimeWatermarkStrategyFactory.build());

        // DWD：清洗明细层
        cleanStream
                .map(new MapFunction<SensorReading, String>() {
                    @Override
                    public String map(SensorReading value) {
                        return CsvUtils.toSensorCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwdSensorCleanSink());

        // DWS：窗口特征层 (你的特征流在这里已经算好了)
        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingEventTimeWindows.of(Time.seconds(JobConfig.WINDOW_SECONDS)))
                .process(new FeatureWindowProcessFunction());

        featureStream.print("〖DWS特征〗--> ");

        // 继续往 HDFS 写离线数据 (原有的逻辑保留)
        featureStream
                .map(new MapFunction<FeatureVector, String>() {
                    @Override
                    public String map(FeatureVector value) {
                        return CsvUtils.toFeatureCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwsFeatureSink());

        // =====================================================================
        // ================== 新增的 AI 在线推理链路 (Branch B) ==================
        // =====================================================================

        // 1. 获取 PMML 文件的路径 (为了防止 Windows 路径踩坑，建议第一次测试直接写死本地绝对路径)
        // 请确保下面这个路径里确实有 model.pmml 文件！！！
        String pmmlPath = "D:/Javatest/zephyr-watch/src/main/resources/models/model.pmml";

        // 如果你想动态获取 resources 目录下的路径，可以用下面两行替换上一行：
        // URL resource = OfflineWarehouseJob.class.getResource("/models/model.pmml");
        // String pmmlPath = resource.getPath().replaceFirst("^/(.:/)", "$1");

        // 2. 将已经算好的 featureStream 接入你的预测算子！
        SingleOutputStreamOperator<RulPrediction> predictionStream = featureStream
                .map(new RulPredictFunction(pmmlPath))
                .name("PMML_RUL_Prediction");

        // 3. 终极点火测试：将预测算出的“剩余寿命”打印到控制台！
        predictionStream.print("🔥🔥🔥 实时寿命预测结果");

        // =====================================================================
        // ================== 新增的 Redis 实时热点缓存链路 ======================
        // =====================================================================

        // 4. 配置 Flink 专属的 Redis 连接池
        org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig conf =
                new org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig.Builder()
                        .setHost("127.0.0.1")
                        .setPort(6379)
                        .build();

        // 5. 将预测流直接打入 Redis！
        predictionStream.addSink(new org.apache.flink.streaming.connectors.redis.RedisSink<>(conf, new com.zephyr.watch.sink.RulRedisMapper()))
                .name("Redis_RUL_Sink");

        // =====================================================================

        // 触发执行 (这行是整个程序的最后一行)
        env.execute(JobConfig.OFFLINE_JOB_NAME);
    }
}