package com.zephyr.watch.flink.app;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.SensorReading;
import com.zephyr.watch.flink.process.EventTimeWatermarkStrategyFactory;
import com.zephyr.watch.flink.process.FeatureWindowProcessFunction;
import com.zephyr.watch.flink.process.RulPredictFunction;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

import java.util.ArrayList;
import java.util.List;

public class LocalDebugJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(1);

        List<SensorReading> data = new ArrayList<SensorReading>();
        long baseTs = 1_700_000_000_000L;

        data.add(new SensorReading(1, 1, 20.0, 300.0, 800.0, baseTs));
        data.add(new SensorReading(1, 2, 21.0, 300.6, 801.0, baseTs + 500));
        data.add(new SensorReading(1, 3, 22.0, 301.2, 802.0, baseTs + 1000));
        data.add(new SensorReading(1, 4, 23.0, 301.8, 803.0, baseTs + 1500));

        data.add(new SensorReading(1, 5, 24.0, 302.4, 804.0, baseTs + 2000));
        data.add(new SensorReading(1, 6, 25.0, 303.0, 805.0, baseTs + 2500));
        data.add(new SensorReading(1, 7, 26.0, 303.6, 806.0, baseTs + 3000));
        data.add(new SensorReading(1, 8, 27.0, 304.2, 807.0, baseTs + 3500));

        data.add(new SensorReading(1, 9, 28.0, 304.8, 808.0, baseTs + 3200));
        data.add(new SensorReading(1, 10, 29.0, 305.4, 809.0, baseTs + 4000));
        data.add(new SensorReading(1, 11, 30.0, 306.0, 810.0, baseTs + 4500));
        data.add(new SensorReading(1, 12, 31.0, 306.6, 811.0, baseTs + 5000));

        env.fromCollection(data)
                .assignTimestampsAndWatermarks(EventTimeWatermarkStrategyFactory.build())
                .keyBy(SensorReading::getMachineId)
                .window(TumblingEventTimeWindows.of(Time.seconds(2)))
                .process(new FeatureWindowProcessFunction())
                .map(new RulPredictFunction(JobConfig.DEFAULT_PMML_MODEL_PATH))
                .print("LOCAL_RUL_PREDICTION -> ");

        env.execute(JobConfig.LOCAL_DEBUG_JOB_NAME);
    }
}
