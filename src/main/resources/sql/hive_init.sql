CREATE DATABASE IF NOT EXISTS zephyr_dw;
USE zephyr_dw;

-- DWD：清洗明细层
DROP TABLE IF EXISTS dwd_sensor_clean;
CREATE EXTERNAL TABLE dwd_sensor_clean (
    machine_id INT,
    cycle INT,
    pressure DOUBLE,
    temperature DOUBLE,
    speed DOUBLE,
    event_time BIGINT
)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/zephyr/dwd/sensor_clean';

-- DWS：窗口特征层
DROP TABLE IF EXISTS dws_device_feature;
CREATE EXTERNAL TABLE dws_device_feature (
    machine_id          INT,
    window_start        BIGINT,
    window_end          BIGINT,
    sample_count        INT,
    cycle_start         INT,
    cycle_end           INT,
    pressure_min        DOUBLE,
    pressure_max        DOUBLE,
    pressure_avg        DOUBLE,
    pressure_std        DOUBLE,
    pressure_trend      DOUBLE,
    temperature_min     DOUBLE,
    temperature_max     DOUBLE,
    temperature_avg     DOUBLE,
    temperature_std     DOUBLE,
    temperature_trend   DOUBLE,
    speed_min           DOUBLE,
    speed_max           DOUBLE,
    speed_avg           DOUBLE,
    speed_std           DOUBLE,
    speed_trend         DOUBLE
)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/zephyr/dws/device_feature';