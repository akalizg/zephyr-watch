# P1-4 人工审核闭环演示

## 目标

本演示用于说明 Zephyr Watch 已具备“告警产生 -> 人工审核 -> 审核标签写入 -> 反馈样本生成 -> 增量训练脚本读取”的闭环能力。当前实现覆盖人工审核回流、反馈样本生成和增量训练脚本读取；自动定时训练、模型灰度发布和自动灰度回滚属于后续扩展，不在当前 P1-4 中宣称已完成。

## 闭环链路

1. OnlineInferenceJob 产生 `AlertEvent`，写入 Kafka `alert_event_topic` 和 MySQL `alert_event`。
2. 人工审核在 API 或演示命令中提交审核标签。
3. 审核标签写入 Kafka `review_label_topic`。
4. AlertReviewJob 消费 `review_label_topic`，写入 `review_label_feedback`。
5. AlertReviewJob 根据 `alert_event` 和 `online_feature_snapshot` 关联生成 `feedback_training_sample`。
6. `zephyr-ml/train/incremental_retrain.py` 调用学习接口导出审核标签和反馈样本，合并到训练集后再触发基线训练。

## 相关表

- `alert_event`：保存告警事件，包括 `alert_id`、`machine_id`、`risk_probability`、`risk_level`、`alert_type`、`event_time`。
- `alert_review`：保存 API 人工审核记录，便于查询原始审核动作。
- `review_label_feedback`：保存回流到学习链路的审核标签，是人工反馈的稳定出口表。
- `feedback_training_sample`：保存由审核标签关联出的训练样本，包含窗口特征、RUL、风险标签和审核标签。

## 相关 Kafka Topic

- `review_label_topic`：人工审核标签回流 Topic。默认值来自 `KafkaConfig.REVIEW_LABEL_TOPIC`，也可以通过环境变量 `ZEPHYR_REVIEW_LABEL_TOPIC` 覆盖。

## 相关接口

- `GET /api/learning/review-labels`：导出审核标签，供增量训练脚本读取。
- `GET /api/learning/feedback-samples`：导出反馈训练样本，供增量训练脚本合并到训练集。

## 相关脚本

- `zephyr-ml/train/incremental_retrain.py`：导出审核标签和反馈训练样本，规范化字段，合并训练集，并在样本量满足要求时复用 `train_baselines.py`。
- `zephyr-ml/data/mock_review_feedback.py`：输出一条标准审核标签 JSON，适合演示或复制到 Kafka producer。

## 模拟审核标签 JSON

```json
{
  "alertId": "ALERT-xxx",
  "reviewLabel": "TRUE_POSITIVE",
  "reviewer": "admin",
  "reviewComment": "现场确认设备存在异常",
  "eventTime": 1776595125000
}
```

如果想生成当前时间戳的 mock 消息，可以运行：

```powershell
cd D:\Javatest\zephyr-watch
zephyr-ml\.venv1\Scripts\python.exe zephyr-ml\data\mock_review_feedback.py
```

## Kafka 写入命令

Windows PowerShell 示例：

```powershell
$env:KAFKA_HOME="D:\kafka"
@'{"alertId":"ALERT-xxx","reviewLabel":"TRUE_POSITIVE","reviewer":"admin","reviewComment":"现场确认设备存在异常","eventTime":1776595125000}'@ | & "$env:KAFKA_HOME\bin\windows\kafka-console-producer.bat" --bootstrap-server 127.0.0.1:9092 --topic review_label_topic
```

Linux 或 Git Bash 示例：

```bash
echo '{"alertId":"ALERT-xxx","reviewLabel":"TRUE_POSITIVE","reviewer":"admin","reviewComment":"现场确认设备存在异常","eventTime":1776595125000}' \
  | kafka-console-producer --bootstrap-server 127.0.0.1:9092 --topic review_label_topic
```

注意：`alertId` 最好替换为 `alert_event` 表中真实存在的告警 ID。只有能关联到告警和窗口特征时，`feedback_training_sample` 才能生成完整训练样本。

## 启动 AlertReviewJob

```powershell
cd D:\Javatest\zephyr-watch
mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java "-Dexec.mainClass=com.zephyr.watch.flink.app.AlertReviewJob"
```

## MySQL 验证 SQL

```sql
SELECT * FROM review_label_feedback ORDER BY created_at DESC LIMIT 10;
SELECT * FROM feedback_training_sample ORDER BY created_at DESC LIMIT 10;
```

如果 `review_label_feedback` 有数据但 `feedback_training_sample` 没数据，优先检查：

1. `alertId` 是否存在于 `alert_event`。
2. `online_feature_snapshot` 是否有同一设备的窗口特征。
3. `reviewLabel` 是否可以转换为训练标签，例如 `TRUE_POSITIVE`、`CONFIRMED_RISK`、`FALSE_POSITIVE`、`NORMAL`。

## 增量训练脚本运行命令

```powershell
cd D:\Javatest\zephyr-watch\zephyr-ml
.venv1\Scripts\python.exe train\incremental_retrain.py
```

如果只是演示导出和样本不足时的安全跳过，可以使用较小阈值：

```powershell
cd D:\Javatest\zephyr-watch\zephyr-ml
.venv1\Scripts\python.exe train\incremental_retrain.py --min-labels 1
```

## 答辩讲法

这部分可以强调系统不是只做一次性离线训练，而是预留了可演示的学习闭环：告警进入人工审核，审核标签进入 Kafka，Flink 将标签和实时特征关联成反馈训练样本，Python 增量训练脚本再读取这些样本。当前没有宣称自动定时调度和模型灰度发布已完成，这两项可以作为工程化后续扩展。
