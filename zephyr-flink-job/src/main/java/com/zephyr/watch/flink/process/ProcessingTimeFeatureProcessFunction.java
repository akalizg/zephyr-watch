package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.SensorReading;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public class ProcessingTimeFeatureProcessFunction
        extends KeyedProcessFunction<Integer, SensorReading, FeatureVector> {

    private final long windowMillis;
    private final long slideMillis;

    private transient ListState<TimedReading> readings;
    private transient ValueState<Long> nextTimer;

    public ProcessingTimeFeatureProcessFunction(int windowSeconds, int slideSeconds) {
        this.windowMillis = windowSeconds * 1000L;
        this.slideMillis = slideSeconds * 1000L;
    }

    @Override
    public void open(Configuration parameters) {
        System.out.println("PROCESSING-FEATURE-FUNCTION-VERSION=direct-v2"
                + ", windowMillis=" + windowMillis
                + ", slideMillis=" + slideMillis);
        readings = getRuntimeContext().getListState(
                new ListStateDescriptor<TimedReading>("processing-time-readings", TimedReading.class)
        );
        nextTimer = getRuntimeContext().getState(
                new ValueStateDescriptor<Long>("next-processing-feature-timer", Types.LONG)
        );
    }

    @Override
    public void processElement(SensorReading value,
                               Context ctx,
                               Collector<FeatureVector> out) throws Exception {
        long now = ctx.timerService().currentProcessingTime();
        readings.add(new TimedReading(value, now));

        long cutoff = now - windowMillis;
        List<TimedReading> kept = new ArrayList<TimedReading>();
        List<SensorReading> window = new ArrayList<SensorReading>();

        Iterator<TimedReading> iterator = readings.get().iterator();
        while (iterator.hasNext()) {
            TimedReading item = iterator.next();
            if (item.arrivalTime >= cutoff) {
                kept.add(item);
                window.add(item.reading);
            }
        }

        readings.update(kept);
        if (!window.isEmpty()) {
            FeatureVector featureVector = buildFeatureVector(ctx.getCurrentKey(), now - windowMillis, now, window);
            System.out.println("FEATURE-WINDOW-TRIGGER machineId=" + featureVector.getMachineId()
                    + ", windowStart=" + featureVector.getWindowStart()
                    + ", windowEnd=" + featureVector.getWindowEnd()
                    + ", sampleCount=" + featureVector.getSampleCount());
            out.collect(featureVector);
        }
    }

    @Override
    public void onTimer(long timestamp,
                        OnTimerContext ctx,
                        Collector<FeatureVector> out) throws Exception {
        long cutoff = timestamp - windowMillis;
        List<TimedReading> kept = new ArrayList<TimedReading>();
        List<SensorReading> window = new ArrayList<SensorReading>();

        Iterator<TimedReading> iterator = readings.get().iterator();
        while (iterator.hasNext()) {
            TimedReading item = iterator.next();
            if (item.arrivalTime >= cutoff) {
                kept.add(item);
                window.add(item.reading);
            }
        }

        readings.update(kept);
        if (!window.isEmpty()) {
            out.collect(buildFeatureVector(ctx.getCurrentKey(), timestamp - windowMillis, timestamp, window));
        }

        long followingTimer = timestamp + slideMillis;
        ctx.timerService().registerProcessingTimeTimer(followingTimer);
        nextTimer.update(followingTimer);
    }

    private long alignNextTimer(long now) {
        return ((now / slideMillis) + 1L) * slideMillis;
    }

    private FeatureVector buildFeatureVector(Integer machineId,
                                             long windowStart,
                                             long windowEnd,
                                             List<SensorReading> elements) {
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

        double pressureAvg = pressureSum / count;
        double temperatureAvg = temperatureSum / count;
        double speedAvg = speedSum / count;

        return new FeatureVector(
                machineId,
                windowStart,
                windowEnd,
                count,
                cycleStart,
                cycleEnd,
                pressureMin,
                pressureMax,
                pressureAvg,
                std(pressureSumSq, pressureAvg, count),
                lastPressure - firstPressure,
                temperatureMin,
                temperatureMax,
                temperatureAvg,
                std(temperatureSumSq, temperatureAvg, count),
                lastTemperature - firstTemperature,
                speedMin,
                speedMax,
                speedAvg,
                std(speedSumSq, speedAvg, count),
                lastSpeed - firstSpeed
        );
    }

    private double std(double sumSq, double mean, int count) {
        if (count <= 1) {
            return 0.0D;
        }
        double variance = (sumSq / count) - (mean * mean);
        return Math.sqrt(Math.max(variance, 0.0D));
    }

    public static class TimedReading {
        public SensorReading reading;
        public long arrivalTime;

        public TimedReading() {
        }

        public TimedReading(SensorReading reading, long arrivalTime) {
            this.reading = reading;
            this.arrivalTime = arrivalTime;
        }
    }
}
