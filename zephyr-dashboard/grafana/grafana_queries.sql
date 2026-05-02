-- Risk probability trend by machine.
SELECT
  FROM_UNIXTIME(window_end / 1000) AS time,
  risk_probability,
  machine_id AS metric
FROM device_risk_prediction
WHERE $__timeFilter(FROM_UNIXTIME(window_end / 1000))
ORDER BY time;

-- RUL trend by machine.
SELECT
  FROM_UNIXTIME(window_end / 1000) AS time,
  rul,
  machine_id AS metric
FROM device_risk_prediction
WHERE $__timeFilter(FROM_UNIXTIME(window_end / 1000))
ORDER BY time;

-- Latest high-risk alerts.
SELECT
  FROM_UNIXTIME(event_time / 1000) AS time,
  alert_id,
  machine_id,
  risk_probability,
  rul,
  risk_level,
  alert_type,
  status,
  message
FROM alert_event
WHERE risk_level IN ('HIGH', 'CRITICAL')
ORDER BY event_time DESC
LIMIT 50;

-- Alert count per hour.
SELECT
  $__timeGroup(created_at, '1h') AS time,
  COUNT(*) AS alert_count
FROM alert_event
WHERE $__timeFilter(created_at)
GROUP BY time
ORDER BY time;

-- Maintenance recommendations.
SELECT
  created_at AS time,
  alert_id,
  machine_id,
  action,
  spare_parts,
  work_order_priority,
  similar_case_id,
  score
FROM maintenance_recommendation
ORDER BY created_at DESC
LIMIT 50;

-- Active model version.
SELECT
  COALESCE(deployed_at, created_at) AS time,
  model_version,
  model_type,
  model_uri,
  threshold_uri,
  feature_columns_uri,
  metadata_uri,
  status
FROM model_registry
WHERE status = 'ACTIVE'
ORDER BY deployed_at DESC, created_at DESC
LIMIT 1;
