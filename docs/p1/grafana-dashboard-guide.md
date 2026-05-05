# P1-5 Grafana 业务看板说明

## 目标

本看板用于展示 Zephyr Watch 的业务可观测能力：实时风险预测、风险等级分布、告警类型、维修工单优先级和人工审核反馈。当前 Grafana 主要展示 MySQL 中的业务指标，不强行宣称已经完成 Prometheus/Flink/Kafka/JVM 全链路系统监控。Prometheus 系统监控属于后续扩展。

## 连接 MySQL 数据源

1. 打开 Grafana，进入 `Connections -> Data sources -> Add data source`。
2. 选择 `MySQL`。
3. 配置连接信息：
   - Host: `localhost:3306` 或部署环境中的 MySQL 地址。
   - Database: `zephyr_watch`。
   - User: 与 `StorageConfig.MYSQL_USER` 或 `.env` 一致。
   - Password: 与 `StorageConfig.MYSQL_PASSWORD` 或 `.env` 一致。
4. 点击 `Save & test`，确认显示连接成功。
5. 新建 Dashboard，并为每个 Panel 选择刚创建的 MySQL 数据源。

## 推荐看板布局

第一行放核心总览指标：实时风险预测总数、高风险设备数量、维修工单优先级分布。

第二行放风险分析：不同风险等级分布、设备风险 TopN、最近 30 分钟风险趋势。

第三行放闭环分析：告警类型分布、人工审核标签分布、最新维修建议表格。

## 图表 SQL 与业务说明

### 图表 1：实时风险预测总数

```sql
SELECT COUNT(*) AS prediction_count
FROM device_risk_prediction;
```

建议 Panel 类型：Stat。

证明能力：说明 OnlineInferenceJob 已将实时窗口特征和模型预测结果持续写入 MySQL，形成可查询的在线推理结果资产。

### 图表 2：高风险设备数量

```sql
SELECT COUNT(DISTINCT machine_id) AS high_risk_machine_count
FROM device_risk_prediction
WHERE risk_label = 1 OR risk_level IN ('HIGH', 'CRITICAL');
```

建议 Panel 类型：Stat。

证明能力：说明系统可以从模型标签和风险等级两条路径识别当前高风险设备。

### 图表 3：不同风险等级分布

```sql
SELECT risk_level, COUNT(*) AS prediction_count
FROM device_risk_prediction
GROUP BY risk_level
ORDER BY prediction_count DESC;
```

建议 Panel 类型：Pie chart 或 Bar chart。

证明能力：说明系统可以按 LOW/MEDIUM/HIGH/CRITICAL 观察风险结构，而不是只看单条概率。

### 图表 4：设备风险 TopN

```sql
SELECT machine_id, MAX(risk_probability) AS max_risk_probability
FROM device_risk_prediction
GROUP BY machine_id
ORDER BY max_risk_probability DESC
LIMIT 10;
```

建议 Panel 类型：Bar chart 或 Table。

证明能力：说明运维人员可以快速定位最需要关注的设备。

### 图表 5：告警类型分布

```sql
SELECT alert_type, COUNT(*) AS alert_count
FROM alert_event
GROUP BY alert_type
ORDER BY alert_count DESC;
```

建议 Panel 类型：Bar chart。

证明能力：说明系统不只依赖模型高风险告警，也可以展示 TEMPERATURE_RISING、SPEED_FLUCTUATION、PRESSURE_FLUCTUATION、COMPOSITE_CRITICAL_RISK、CEP_CONSECUTIVE_HIGH_RISK 等多类告警来源。

### 图表 6：维修工单优先级分布

```sql
SELECT work_order_priority, COUNT(*) AS recommendation_count
FROM maintenance_recommendation
GROUP BY work_order_priority
ORDER BY work_order_priority;
```

建议 Panel 类型：Bar chart 或 Pie chart。

证明能力：说明告警已转化为维修建议和 P0/P1/P2/P3 工单优先级，便于答辩展示业务闭环。

### 图表 7：最近 30 分钟风险趋势

```sql
SELECT
  FROM_UNIXTIME(FLOOR(window_end / 300000) * 300) AS time_bucket,
  AVG(risk_probability) AS avg_risk_probability
FROM device_risk_prediction
WHERE window_end >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 30 MINUTE)) * 1000
GROUP BY time_bucket
ORDER BY time_bucket;
```

建议 Panel 类型：Time series。将 `time_bucket` 设置为时间字段。

证明能力：说明系统可以观察近期风险变化趋势，为实时监控展示提供依据。

### 图表 8：人工审核标签分布

```sql
SELECT review_label, COUNT(*) AS label_count
FROM review_label_feedback
GROUP BY review_label
ORDER BY label_count DESC;
```

建议 Panel 类型：Pie chart 或 Bar chart。

证明能力：说明人工审核标签已经沉淀到反馈表，可用于后续增量训练数据分析。

## 常见无数据问题排查

1. 确认 MySQL 初始化脚本已执行：`zephyr-flink-job/src/main/resources/sql/mysql_init.sql`。
2. 确认 OnlineInferenceJob 正在运行，并且 `device_risk_prediction` 有数据。
3. 确认 Kafka 中有传感器数据输入，Producer 或上游数据源没有停止。
4. 如果告警类型分布为空，确认 `risk_label=1` 或特征异常阈值是否被触发。
5. 如果维修工单为空，确认 RecommendJob 已启动并消费 `alert_event` Topic。
6. 如果人工审核标签为空，确认审核接口或 `review_label_topic` 消息已写入。
7. Grafana 查询使用时间过滤时，注意 `window_end` 和 `event_time` 是毫秒时间戳，SQL 中需要除以 1000 后转成 MySQL 时间。
8. 当前文档不覆盖 Prometheus/Flink/Kafka/JVM 全链路系统监控；如果系统指标面板为空，应检查 Prometheus 后续扩展配置，而不是把它作为本业务看板的验收前提。

## 导入业务 Dashboard JSON

Grafana 页面选择 `Dashboards -> New -> Import`，上传 `zephyr-dashboard/grafana/zephyr-watch-business-dashboard.json`，并在导入时将 `${DS_MYSQL}` 绑定到当前 MySQL 数据源即可。
