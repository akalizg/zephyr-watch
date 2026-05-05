-- Zephyr Watch Grafana business dashboard queries.
-- Datasource: MySQL database zephyr_watch.
-- These queries focus on business observability, not Prometheus/Flink/Kafka/JVM system monitoring.

-- Chart 1: 实时风险预测总数
SELECT COUNT(*) AS prediction_count
FROM device_risk_prediction;

-- Chart 2: 高风险设备数量
SELECT COUNT(DISTINCT machine_id) AS high_risk_machine_count
FROM device_risk_prediction
WHERE risk_label = 1 OR risk_level IN ('HIGH', 'CRITICAL');

-- Chart 3: 不同风险等级分布
SELECT risk_level, COUNT(*) AS prediction_count
FROM device_risk_prediction
GROUP BY risk_level
ORDER BY prediction_count DESC;

-- Chart 4: 设备风险 TopN
SELECT machine_id, MAX(risk_probability) AS max_risk_probability
FROM device_risk_prediction
GROUP BY machine_id
ORDER BY max_risk_probability DESC
LIMIT 10;

-- Chart 5: 告警类型分布
SELECT alert_type, COUNT(*) AS alert_count
FROM alert_event
GROUP BY alert_type
ORDER BY alert_count DESC;

-- Chart 6: 维修工单优先级分布
SELECT work_order_priority, COUNT(*) AS recommendation_count
FROM maintenance_recommendation
GROUP BY work_order_priority
ORDER BY work_order_priority;

-- Chart 7: 最近 30 分钟风险趋势
SELECT
  FROM_UNIXTIME(FLOOR(window_end / 300000) * 300) AS time_bucket,
  AVG(risk_probability) AS avg_risk_probability
FROM device_risk_prediction
WHERE window_end >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 30 MINUTE)) * 1000
GROUP BY time_bucket
ORDER BY time_bucket;

-- Chart 8: 人工审核标签分布
SELECT review_label, COUNT(*) AS label_count
FROM review_label_feedback
GROUP BY review_label
ORDER BY label_count DESC;
