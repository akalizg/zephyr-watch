package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RulPrediction;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.common.utils.CsvUtils;
import com.zephyr.watch.flink.process.EventTimeWatermarkStrategyFactory;
import com.zephyr.watch.flink.process.FeatureWindowProcessFunction;
import com.zephyr.watch.flink.process.JsonToSensorReadingMapFunction;
import com.zephyr.watch.flink.process.RulPredictFunction;
import com.zephyr.watch.flink.process.SensorValidationFilter;
import com.zephyr.watch.flink.sink.HdfsCsvSinkFactory;
import com.zephyr.watch.flink.sink.RulRedisMapper;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;

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

        cleanStream
                .map(new MapFunction<SensorReading, String>() {
                    @Override
                    public String map(SensorReading value) {
                        return CsvUtils.toSensorCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwdSensorCleanSink());

        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingEventTimeWindows.of(Time.seconds(JobConfig.WINDOW_SECONDS)))
                .process(new FeatureWindowProcessFunction());

        featureStream.print("DWS_FEATURE -> ");

        featureStream
                .map(new MapFunction<FeatureVector, String>() {
                    @Override
                    public String map(FeatureVector value) {
                        return CsvUtils.toFeatureCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwsFeatureSink());

        SingleOutputStreamOperator<RulPrediction> predictionStream = featureStream
                .map(new RulPredictFunction(JobConfig.DEFAULT_PMML_MODEL_PATH))
                .name("PMML_RUL_Prediction");

        predictionStream.print("RUL_PREDICTION -> ");

        FlinkJedisPoolConfig redisConfig = new FlinkJedisPoolConfig.Builder()
                .setHost("127.0.0.1")
                .setPort(6379)
                .build();

        predictionStream.addSink(new RedisSink<RulPrediction>(redisConfig, new RulRedisMapper()))
                .name("Redis_RUL_Sink");

        env.execute(JobConfig.OFFLINE_JOB_NAME);
    }
}
