package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.common.entity.RulPrediction; // 寮曞叆鏂板啓鐨勯娴嬪疄浣撶被
import com.zephyr.watch.flink.process.RulPredictFunction; // 寮曞叆鏂板啓鐨勯娴嬬畻瀛?
import com.zephyr.watch.flink.process.EventTimeWatermarkStrategyFactory;
import com.zephyr.watch.flink.process.FeatureWindowProcessFunction;
import com.zephyr.watch.flink.process.JsonToSensorReadingMapFunction;
import com.zephyr.watch.flink.process.SensorValidationFilter;
import com.zephyr.watch.flink.sink.HdfsCsvSinkFactory;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import com.zephyr.watch.common.utils.CsvUtils;
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
import com.zephyr.watch.flink.sink.RulRedisMapper;
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

        // DWD锛氭竻娲楁槑缁嗗眰
        cleanStream
                .map(new MapFunction<SensorReading, String>() {
                    @Override
                    public String map(SensorReading value) {
                        return CsvUtils.toSensorCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwdSensorCleanSink());

        // DWS锛氱獥鍙ｇ壒寰佸眰 (浣犵殑鐗瑰緛娴佸湪杩欓噷宸茬粡绠楀ソ浜?
        SingleOutputStreamOperator<FeatureVector> featureStream = cleanStream
                .keyBy(SensorReading::getMachineId)
                .window(TumblingEventTimeWindows.of(Time.seconds(JobConfig.WINDOW_SECONDS)))
                .process(new FeatureWindowProcessFunction());

        featureStream.print("銆朌WS鐗瑰緛銆?-> ");

        // 缁х画寰€ HDFS 鍐欑绾挎暟鎹?(鍘熸湁鐨勯€昏緫淇濈暀)
        featureStream
                .map(new MapFunction<FeatureVector, String>() {
                    @Override
                    public String map(FeatureVector value) {
                        return CsvUtils.toFeatureCsv(value);
                    }
                })
                .sinkTo(HdfsCsvSinkFactory.buildDwsFeatureSink());

        // =====================================================================
        // ================== 鏂板鐨?AI 鍦ㄧ嚎鎺ㄧ悊閾捐矾 (Branch B) ==================
        // =====================================================================

        // 1. 鑾峰彇 PMML 鏂囦欢鐨勮矾寰?(涓轰簡闃叉 Windows 璺緞韪╁潙锛屽缓璁涓€娆℃祴璇曠洿鎺ュ啓姝绘湰鍦扮粷瀵硅矾寰?
        // 璇风‘淇濅笅闈㈣繖涓矾寰勯噷纭疄鏈?model.pmml 鏂囦欢锛侊紒锛?
        String pmmlPath = "D:/Javatest/zephyr-watch/src/main/resources/models/model.pmml";

        // 濡傛灉浣犳兂鍔ㄦ€佽幏鍙?resources 鐩綍涓嬬殑璺緞锛屽彲浠ョ敤涓嬮潰涓よ鏇挎崲涓婁竴琛岋細
        // URL resource = OfflineWarehouseJob.class.getResource("/models/model.pmml");
        // String pmmlPath = resource.getPath().replaceFirst("^/(.:/)", "$1");

        // 2. 灏嗗凡缁忕畻濂界殑 featureStream 鎺ュ叆浣犵殑棰勬祴绠楀瓙锛?
        SingleOutputStreamOperator<RulPrediction> predictionStream = featureStream
                .map(new RulPredictFunction(pmmlPath))
                .name("PMML_RUL_Prediction");

        // 3. 缁堟瀬鐐圭伀娴嬭瘯锛氬皢棰勬祴绠楀嚭鐨勨€滃墿浣欏鍛解€濇墦鍗板埌鎺у埗鍙帮紒
        predictionStream.print("馃敟馃敟馃敟 瀹炴椂瀵垮懡棰勬祴缁撴灉");

        // =====================================================================
        // ================== 鏂板鐨?Redis 瀹炴椂鐑偣缂撳瓨閾捐矾 ======================
        // =====================================================================

        // 4. 閰嶇疆 Flink 涓撳睘鐨?Redis 杩炴帴姹?
        org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig conf =
                new org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig.Builder()
                        .setHost("127.0.0.1")
                        .setPort(6379)
                        .build();

        // 5. 灏嗛娴嬫祦鐩存帴鎵撳叆 Redis锛?
        predictionStream.addSink(new org.apache.flink.streaming.connectors.redis.RedisSink<>(conf, new com.zephyr.watch.flink.sink.RulRedisMapper()))
                .name("Redis_RUL_Sink");

        // =====================================================================

        // 瑙﹀彂鎵ц (杩欒鏄暣涓▼搴忕殑鏈€鍚庝竴琛?
        env.execute(JobConfig.OFFLINE_JOB_NAME);
    }
}
