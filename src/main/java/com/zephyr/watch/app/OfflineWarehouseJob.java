package com.zephyr.watch.app;

import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.model.FeatureVector;
import com.zephyr.watch.model.SensorReading;
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

        // DWS：窗口特征层
        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingEventTimeWindows.of(Time.seconds(JobConfig.WINDOW_SECONDS)))
                .process(new FeatureWindowProcessFunction());

        featureStream.print("〖DWS特征〗--> ");

        featureStream
                .map(new MapFunction<FeatureVector, String>() {
                    @Override
                    public String map(FeatureVector value) {
                        return CsvUtils.toFeatureCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwsFeatureSink());

        env.execute(JobConfig.OFFLINE_JOB_NAME);
    }
}