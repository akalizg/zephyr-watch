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

-- ADS: risk prediction serving layer
DROP TABLE IF EXISTS ads_risk_prediction;
CREATE EXTERNAL TABLE ads_risk_prediction (
    machine_id INT,
    window_start BIGINT,
    window_end BIGINT,
    rul DOUBLE,
    risk_probability DOUBLE,
    risk_label INT,
    risk_level STRING,
    model_version STRING,
    event_time BIGINT
)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/zephyr/ads/risk_prediction';

-- ADS: alert event serving layer
DROP TABLE IF EXISTS ads_alert_event;
CREATE EXTERNAL TABLE ads_alert_event (
    alert_id STRING,
    machine_id INT,
    event_time BIGINT,
    risk_probability DOUBLE,
    rul DOUBLE,
    risk_level STRING,
    alert_type STRING,
    message STRING,
    source STRING,
    status STRING,
    model_version STRING
)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/zephyr/ads/alert_event';

-- ADS: maintenance recommendation serving layer
DROP TABLE IF EXISTS ads_maintenance_recommendation;
CREATE EXTERNAL TABLE ads_maintenance_recommendation (
    recommendation_id STRING,
    alert_id STRING,
    machine_id INT,
    action STRING,
    spare_parts STRING,
    work_order_priority STRING,
    similar_case_id STRING,
    score DOUBLE,
    created_at STRING
)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ','
STORED AS TEXTFILE
LOCATION '/zephyr/ads/maintenance_recommendation';
