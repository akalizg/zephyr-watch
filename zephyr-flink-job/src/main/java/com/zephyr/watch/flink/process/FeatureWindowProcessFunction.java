package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.SensorReading;
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

        int cycleStart = Integer.MAX_VALUE;
        int cycleEnd = Integer.MIN_VALUE;

        double pressureMin = Double.POSITIVE_INFINITY;
        double pressureMax = Double.NEGATIVE_INFINITY;
        double pressureSum = 0.0D;
        double pressureSumSq = 0.0D;

        double temperatureMin = Double.POSITIVE_INFINITY;
        double temperatureMax = Double.NEGATIVE_INFINITY;
        double temperatureSum = 0.0D;
        double temperatureSumSq = 0.0D;

        double speedMin = Double.POSITIVE_INFINITY;
        double speedMax = Double.NEGATIVE_INFINITY;
        double speedSum = 0.0D;
        double speedSumSq = 0.0D;

        long firstTs = Long.MAX_VALUE;
        long lastTs = Long.MIN_VALUE;

        double firstPressure = 0.0D;
        double lastPressure = 0.0D;

        double firstTemperature = 0.0D;
        double lastTemperature = 0.0D;

        double firstSpeed = 0.0D;
        double lastSpeed = 0.0D;

        for (SensorReading reading : elements) {
            count++;

            cycleStart = Math.min(cycleStart, reading.getCycle());
            cycleEnd = Math.max(cycleEnd, reading.getCycle());

            double pressure = reading.getPressure();
            double temperature = reading.getTemperature();
            double speed = reading.getSpeed();

            pressureMin = Math.min(pressureMin, pressure);
            pressureMax = Math.max(pressureMax, pressure);
            pressureSum += pressure;
            pressureSumSq += pressure * pressure;

            temperatureMin = Math.min(temperatureMin, temperature);
            temperatureMax = Math.max(temperatureMax, temperature);
            temperatureSum += temperature;
            temperatureSumSq += temperature * temperature;

            speedMin = Math.min(speedMin, speed);
            speedMax = Math.max(speedMax, speed);
            speedSum += speed;
            speedSumSq += speed * speed;

            long ts = reading.getEventTime();
            if (ts < firstTs) {
                firstTs = ts;
                firstPressure = pressure;
                firstTemperature = temperature;
                firstSpeed = speed;
            }
            if (ts > lastTs) {
                lastTs = ts;
                lastPressure = pressure;
                lastTemperature = temperature;
                lastSpeed = speed;
            }
        }

        if (count == 0) {
            return;
        }

        double pressureAvg = pressureSum / count;
        double temperatureAvg = temperatureSum / count;
        double speedAvg = speedSum / count;

        double pressureStd = std(pressureSumSq, pressureAvg, count);
        double temperatureStd = std(temperatureSumSq, temperatureAvg, count);
        double speedStd = std(speedSumSq, speedAvg, count);

        double pressureTrend = lastPressure - firstPressure;
        double temperatureTrend = lastTemperature - firstTemperature;
        double speedTrend = lastSpeed - firstSpeed;

        FeatureVector vector = new FeatureVector(
                machineId,
                context.window().getStart(),
                context.window().getEnd(),
                count,
                cycleStart,
                cycleEnd,
                pressureMin,
                pressureMax,
                pressureAvg,
                pressureStd,
                pressureTrend,
                temperatureMin,
                temperatureMax,
                temperatureAvg,
                temperatureStd,
                temperatureTrend,
                speedMin,
                speedMax,
                speedAvg,
                speedStd,
                speedTrend
        );

        out.collect(vector);
    }

    private double std(double sumSq, double mean, int count) {
        if (count <= 1) {
            return 0.0D;
        }
        double variance = (sumSq / count) - (mean * mean);
        if (variance < 0.0D) {
            variance = 0.0D;
        }
        return Math.sqrt(variance);
    }
}
