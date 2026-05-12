package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.SensorReading;
import org.apache.flink.api.common.functions.FilterFunction;

public class SensorValidationFilter implements FilterFunction<SensorReading> {

    @Override
    public boolean filter(SensorReading value) {
        if (value == null) {
            return false;
        }
        if (value.getMachineId() == null || value.getMachineId() <= 0) {
            return false;
        }
        if (value.getCycle() == null || value.getCycle() < 0) {
            return false;
        }
        if (value.getPressure() == null || value.getTemperature() == null || value.getSpeed() == null) {
            return false;
        }
        if (value.getPressure().isNaN() || value.getTemperature().isNaN() || value.getSpeed().isNaN()) {
            return false;
        }
        if (value.getPressure().isInfinite() || value.getTemperature().isInfinite() || value.getSpeed().isInfinite()) {
            return false;
        }
        return value.getEventTime() != null && value.getEventTime() > 0L;
    }
}
