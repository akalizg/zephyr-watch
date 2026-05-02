package com.zephyr.watch.flink.app;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.MaintenanceRecommendation;
import com.zephyr.watch.flink.process.FlinkRuntimeConfigurer;
import com.zephyr.watch.flink.process.MaintenanceRecommendationFunction;
import com.zephyr.watch.flink.sink.MySqlSinkFactory;
import com.zephyr.watch.flink.sink.RecommendationRedisMapper;
import com.zephyr.watch.flink.source.SensorKafkaSourceFactory;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;

public class RecommendJob {

    public static void main(String[] args) throws Exception {
        boolean debugPrintEnabled = args.length > 0 && Boolean.parseBoolean(args[0]);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        FlinkRuntimeConfigurer.configureReliableStreaming(env);

        SingleOutputStreamOperator<AlertEvent> alertStream = env.fromSource(
                        SensorKafkaSourceFactory.buildAlertEventSource(),
                        WatermarkStrategy.noWatermarks(),
                        "Kafka_Alert_Event_Source"
                )
                .map(new MapFunction<String, AlertEvent>() {
                    @Override
                    public AlertEvent map(String value) {
                        return JSON.parseObject(value, AlertEvent.class);
                    }
                })
                .name("Parse_Alert_Event");

        SingleOutputStreamOperator<MaintenanceRecommendation> recommendationStream = alertStream
                .map(new MaintenanceRecommendationFunction())
                .name("Rule_And_Similar_Case_Recommendation");

        if (debugPrintEnabled) {
            recommendationStream.print("MAINTENANCE_RECOMMENDATION");
        }

        recommendationStream.addSink(MySqlSinkFactory.buildRecommendationSink())
                .name("MySQL_Maintenance_Recommendation_Sink");

        FlinkJedisPoolConfig redisConfig = new FlinkJedisPoolConfig.Builder()
                .setHost(StorageConfig.REDIS_HOST)
                .setPort(StorageConfig.REDIS_PORT)
                .build();
        recommendationStream.addSink(new RedisSink<MaintenanceRecommendation>(redisConfig, new RecommendationRedisMapper()))
                .name("Redis_Maintenance_Recommendation_Sink");

        env.execute("Zephyr-Watch Maintenance Recommendation Job");
    }
}
