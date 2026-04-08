package com.zephyr.watch.app;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.bean.SensorReading;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.time.Duration;

public class ZephyrWatchMaster {
    public static void main(String[] args) throws Exception {
        // ================= 新增这一行：伪装成 root 用户访问 HDFS =================
        System.setProperty("HADOOP_USER_NAME", "root");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(1);

        // 👇 ================== 新增这一行：开启自动存档，每 10 秒强制刷一次盘 ==================
        env.enableCheckpointing(10000);

        // 1. 读取 Kafka
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers("192.168.88.161:9092") // 你的 Kafka IP
                .setTopics("iot_sensor_data")
                .setGroupId("zephyr-compute-group")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStreamSource<String> kafkaStream = env.fromSource(
                kafkaSource,
                WatermarkStrategy.noWatermarks(),
                "Kafka_Sensor_Source"
        );

        // 2. 解析 JSON
        SingleOutputStreamOperator<SensorReading> sensorStream = kafkaStream
                .map(json -> {
                    try {
                        return JSON.parseObject(json, SensorReading.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(data -> data != null);

        // 3. 窗口特征计算
        SingleOutputStreamOperator<String> featureStream = sensorStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(5)))
                .process(new ProcessWindowFunction<SensorReading, String, Integer, TimeWindow>() {
                    @Override
                    public void process(Integer machineId, Context context, Iterable<SensorReading> elements, Collector<String> out) {
                        double sumS3 = 0;
                        double maxS2 = Double.MIN_VALUE;
                        int count = 0;

                        for (SensorReading reading : elements) {
                            sumS3 += reading.getS3();
                            maxS2 = Math.max(maxS2, reading.getS2());
                            count++;
                        }
                        double avgS3 = sumS3 / count;

                        // 格式化为 CSV 格式：机组ID,数据量,最大压力,平均温度
                        String result = String.format("%d,%d,%.2f,%.2f", machineId, count, maxS2, avgS3);
                        out.collect(result);
                    }
                });

        // 4. 构建 HDFS Sink (注意这里的 IP 必须是你虚拟机的 IP，8020 是 Hadoop 默认端口)
        FileSink<String> hdfsSink = FileSink
                .<String>forRowFormat(new Path("hdfs://192.168.88.161:8020/zephyr/features"), new SimpleStringEncoder<>("UTF-8"))
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withRolloverInterval(Duration.ofMinutes(1)) // 每 1 分钟生成一个新文件
                                .withInactivityInterval(Duration.ofSeconds(30))
                                .build())
                .build();

        // 5. 将数据双写：同时打印到控制台，并写入 HDFS
        featureStream.print("【特征已提取，准备写入HDFS】--> ");
        featureStream.sinkTo(hdfsSink);

        env.execute("Zephyr-Watch Window Feature Extractor to HDFS");
    }
}