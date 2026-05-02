package com.zephyr.watch.common.utils;

import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.SensorReading;

import java.util.Locale;

public final class CsvUtils {

    private CsvUtils() {
    }

    public static String toSensorCsv(SensorReading r) {
        return String.format(
                Locale.US,
                "%d,%d,%.6f,%.6f,%.6f,%d",
                r.getMachineId(),
                r.getCycle(),
                r.getPressure(),
                r.getTemperature(),
                r.getSpeed(),
                r.getEventTime()
        );
    }

    public static String toFeatureCsv(FeatureVector f) {
        return String.format(
                Locale.US,
                "%d,%d,%d,%d,%d,%d," +
                        "%.6f,%.6f,%.6f,%.6f,%.6f," +
                        "%.6f,%.6f,%.6f,%.6f,%.6f," +
                        "%.6f,%.6f,%.6f,%.6f,%.6f",
                f.getMachineId(),
                f.getWindowStart(),
                f.getWindowEnd(),
                f.getSampleCount(),
                f.getCycleStart(),
                f.getCycleEnd(),

                f.getPressureMin(),
                f.getPressureMax(),
                f.getPressureAvg(),
                f.getPressureStd(),
                f.getPressureTrend(),

                f.getTemperatureMin(),
                f.getTemperatureMax(),
                f.getTemperatureAvg(),
                f.getTemperatureStd(),
                f.getTemperatureTrend(),

                f.getSpeedMin(),
                f.getSpeedMax(),
                f.getSpeedAvg(),
                f.getSpeedStd(),
                f.getSpeedTrend()
        );
    }
}
