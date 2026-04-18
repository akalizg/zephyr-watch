package com.zephyr.watch.app;

import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.model.FeatureVector;
import com.zephyr.watch.model.SensorReading;
import com.zephyr.watch.process.FeatureWindowProcessFunction;
import com.zephyr.watch.process.JsonToSensorReadingMapFunction;
import com.zephyr.watch.process.SensorValidationFilter;
import com.zephyr.watch.sink.HdfsFeatureSinkFactory;
import com.zephyr.watch.source.SensorKafkaSourceFactory;
import com.zephyr.watch.util.JsonUtils;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

public class OfflineFeatureJob {

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

        SingleOutputStreamOperator<SensorReading> sensorStream = kafkaStream
                .map(new JsonToSensorReadingMapFunction())
                .filter(new SensorValidationFilter());

        SingleOutputStreamOperator<FeatureVector> featureStream = sensorStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(JobConfig.WINDOW_SECONDS)))
                .process(new FeatureWindowProcessFunction());

        featureStream.print("〖特征已提取，准备写入HDFS〗--> ");

        featureStream
                .map(new MapFunction<FeatureVector, String>() {
                    @Override
                    public String map(FeatureVector value) {
                        return JsonUtils.toJsonString(value);
                    }
                })
                .sinkTo(HdfsFeatureSinkFactory.build());

        env.execute(JobConfig.OFFLINE_JOB_NAME);
    }
}