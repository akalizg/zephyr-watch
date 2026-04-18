package com.zephyr.watch.app;

import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.model.SensorReading;
import com.zephyr.watch.process.FeatureWindowProcessFunction;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

public class LocalDebugJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(1);

        env.addSource(new SourceFunction<SensorReading>() {
                    private volatile boolean running = true;

                    @Override
                    public void run(SourceContext<SensorReading> ctx) throws Exception {
                        long base = System.currentTimeMillis();

                        for (int i = 0; i < 12 && running; i++) {
                            SensorReading reading = new SensorReading(
                                    1,
                                    i + 1,
                                    20.0 + i,
                                    300.0 + i * 0.8,
                                    800.0 + i * 1.5,
                                    base + i * 500
                            );

                            synchronized (ctx.getCheckpointLock()) {
                                ctx.collect(reading);
                            }

                            Thread.sleep(300L);
                        }
                    }

                    @Override
                    public void cancel() {
                        running = false;
                    }
                })
                .keyBy(SensorReading::getMachineId)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(2)))
                .process(new FeatureWindowProcessFunction())
                .print("〖本地调试特征〗--> ");

        env.execute(JobConfig.LOCAL_DEBUG_JOB_NAME);
    }
}