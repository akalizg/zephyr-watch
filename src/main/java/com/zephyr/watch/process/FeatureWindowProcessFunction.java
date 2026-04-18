package com.zephyr.watch.process;

import com.zephyr.watch.model.FeatureVector;
import com.zephyr.watch.model.SensorReading;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

public class FeatureWindowProcessFunction
        extends ProcessWindowFunction<SensorReading, FeatureVector, Integer, TimeWindow> {

    @Override
    public void process(Integer machineId,
                        Context context,
                        Iterable<SensorReading> elements,
                        Collector<FeatureVector> out) {

        int count = 0;
        double pressureSum = 0.0D;
        double pressureMax = Double.MIN_VALUE;
        double temperatureSum = 0.0D;
        double speedSum = 0.0D;

        for (SensorReading reading : elements) {
            count++;
            pressureSum += reading.getPressure();
            pressureMax = Math.max(pressureMax, reading.getPressure());
            temperatureSum += reading.getTemperature();
            speedSum += reading.getSpeed();
        }

        if (count == 0) {
            return;
        }

        FeatureVector vector = new FeatureVector(
                machineId,
                context.window().getStart(),
                context.window().getEnd(),
                count,
                pressureMax,
                pressureSum / count,
                temperatureSum / count,
                speedSum / count
        );

        out.collect(vector);
    }
}