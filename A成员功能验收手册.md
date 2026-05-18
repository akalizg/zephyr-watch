# A成员功能验收手册（在线推理增强）

> 适用对象：组长/验收老师（即使不了解实现细节，也能按步骤完成验收）  
> 适用范围：A 成员负责的 4 项能力
>
> 1. REST 风险推理主链路改造为 Flink Async I/O（默认主链路）
> 2. ONNX Runtime 在线推理接入
> 3. TF Serving 推理接入
> 4. 无效传感器 Side Output 接入 `invalid_sensor_topic`

---

## 1. 验收目标与通过标准

### 1.1 验收目标

确认 A 成员改造已达到“主链路已改造、可运行、可回退、可切换、可验证”的状态，而不是“仅代码预留”。

### 1.2 总通过标准（全部满足才算通过）

1. 默认配置下，REST 推理走 Async I/O 主链路，系统可稳定出数。
2. 打开应急开关后，可回退到同步 REST 推理，系统仍可出数。
3. ONNX 后端可切换成功并产出推理结果。
4. TF Serving 后端可切换成功并产出推理结果。
5. 无效传感器消息可被旁路到 `invalid_sensor_topic`。
6. 切回团队默认配置后，其他成员主流程（API、OnlineInference、Recommend、AlertReview、Producer）不受影响。

---

## 2. 验收前准备（必须完成）

## 2.1 代码与目录

在项目根目录执行（示例路径按你本机调整）：

```powershell
cd D:\dashuju\homework\zephyr-watch
```

确认存在以下文件：

- `run-all.bat`
- `.env`（若没有就从 `.env.example` 复制）
- `zephyr-flink-job/src/main/java/com/zephyr/watch/flink/app/OnlineInferenceJob.java`
- `zephyr-ml/train/export_onnx.py`
- `zephyr-ml/train/export_tf_serving_saved_model.py`

若 `.env` 不存在：

```powershell
Copy-Item .env.example .env
```

## 2.2 外部依赖准备

必须可用：

1. Kafka（含输入 topic 和输出 topic）
2. MySQL（含 `zephyr_watch` 库和 `mysql_init.sql` 建表结果）
3. Redis
4. Python 环境（能运行 `zephyr-ml/serve/risk_model_service.py`）
5. Java 与 Maven

可选但建议：

1. Docker（用于启动 Grafana/TF Serving 容器）

## 2.3 Kafka Topic 检查（在 Kafka 机器执行）

```bash
cd /export/server/kafka_2.12-2.4.1/bin
./kafka-topics.sh --list --bootstrap-server 192.168.88.161:9092
```

至少应看到：

- `iot_sensor_data`（或你 `.env` 中 `ZEPHYR_INPUT_TOPIC` 指定的输入 topic）
- `risk_prediction_topic`
- `alert_event_topic`
- `review_label_topic`
- `invalid_sensor_topic`

若缺失 `invalid_sensor_topic`，创建：

```bash
./kafka-topics.sh --create --topic invalid_sensor_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
```

## 2.4 MySQL 建表检查

如果历史环境不确定，请重新执行：

- `zephyr-flink-job/src/main/resources/sql/mysql_init.sql`

至少确认存在表：

- `device_risk_prediction`
- `alert_event`
- `online_feature_snapshot`
- `maintenance_recommendation`

## 2.5 一次性健康检查

在 Windows PowerShell 执行：

```powershell
where.exe java
where.exe mvn
```

若命令不存在，先补齐环境变量再继续验收。

---

## 3. 启动与通用验证基线

每个用例都建议先停干净，再启动。

## 3.1 停止历史进程

下述命令进入到zephyr-watch（项目根目录）目录执行
```powershell
powershell -ExecutionPolicy Bypass -File .\stop-zephyr.ps1
```

## 3.2 启动全链路

```powershell
.\run-all.bat zephyr-flink-job\src\main\resources\models\model.pmml risk-classifier-rest-v1 true http://localhost:5001/api/risk/score
```

> 第 3 个参数 `true` 是为了让日志更详细，便于组长验收。

## 3.3 通用健康检查

等待 30~90 秒后执行：

```powershell
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:5001/api/risk/health
curl.exe -s http://localhost:8080/api/dashboard/overview
```

预期：

1. API health 返回 `"status":"UP"`
2. 风险模型服务 health 返回 `"success":true`
3. dashboard overview 返回带 `predictionCount` 等统计字段的 JSON

---

## 4. 用例 A-1：验收 REST Async I/O 主链路（核心）

停止历史进程！！
在项目根目录执行
.\stop-zephyr.psl

## 4.1 配置

编辑项目根目录 `.env`，确认：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=rest
ZEPHYR_FORCE_SYNC_REST_INFERENCE=false
ZEPHYR_ENABLE_INVALID_SENSOR_SINK=false
```

保存后，执行“启动全链路”。

## 4.2 观察日志（OnlineInferenceJob 窗口）

必须看到配置打印里至少包含：

- `riskInferenceBackend=rest`
- `useAsyncRestMainline=true`
- `forceSyncRestInference=false`

这 3 条同时成立，表示 REST 默认确实走 Async 主链路。

## 4.3 数据出数验证（MySQL）

在 MySQL 客户端执行：

```sql
SELECT COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 5 MINUTE;
```

预期：`cnt > 0`。

再查模型版本分布：

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：有持续新增行。

## 4.4 通过标准

1. 日志明确 `useAsyncRestMainline=true`
2. `device_risk_prediction` 持续新增

---

## 5. 用例 A-1R：验收应急回退同步 REST（可回退性）

## 5.1 配置

将 `.env` 改为：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=rest
ZEPHYR_FORCE_SYNC_REST_INFERENCE=true
```

重启全链路。

## 5.2 观察日志

OnlineInferenceJob 窗口应出现：

- `useAsyncRestMainline=false`
- `ZEPHYR_FORCE_SYNC_REST_INFERENCE=true, fallback to sync REST inference.`

## 5.3 出数验证

重复执行第 4.3 节 SQL，预期仍持续出数。

## 5.4 通过标准

1. 可从 Async 主链路切换为同步 REST
2. 回退后系统仍可正常出结果

---

## 6. 用例 A-2：验收无效传感器旁路到 invalid_sensor_topic

## 6.1 配置

`.env` 设置：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=rest
ZEPHYR_FORCE_SYNC_REST_INFERENCE=false
ZEPHYR_ENABLE_INVALID_SENSOR_SINK=true
```

重启全链路。

## 6.2 日志预检查

OnlineInferenceJob 配置打印中应包含：

- `enableInvalidSensorSink=true`

(ps:在窗口的第1-4行找，后续关于这个日志检查都是)

## 6.3 发送“故意非法”消息（超详细步骤）

下面请在 **Kafka 所在机器** 上操作（通常是 `node1`），并且开两个终端窗口：

### 6.3.1 先确认输入 topic 名称

在项目根目录查看 `.env`：

```bash
grep ^ZEPHYR_INPUT_TOPIC= D:/dashuju/homework/zephyr-watch/.env
```

如果你是在 Linux 服务器上且没有这个 Windows 路径，可直接按你环境里的 `.env` 路径查看。  
若没配置，默认按 `iot_sensor_data` 处理。

### 6.3.2 终端 A：先启动“观察窗口”（消费 invalid topic）

先进入 Kafka 脚本目录（注意必须在 `bin` 下）：

```bash
cd /export/server/kafka_2.12-2.4.1/bin
```

执行：

```bash
./kafka-console-consumer.sh --bootstrap-server 192.168.88.161:9092 --topic invalid_sensor_topic --from-beginning --timeout-ms 30000
```

说明：这个窗口先不要关，保持等待消息。

### 6.3.3 终端 B：发送一条非法消息到输入 topic

同样先进入 Kafka `bin` 目录：

```bash
cd /export/server/kafka_2.12-2.4.1/bin
```

执行（以 `iot_sensor_data` 为例）：

```bash
echo 'A_ACCEPT_INVALID_20260518_NOT_JSON' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

这条消息不是 JSON，`ParseAndValidateSensorProcessFunction` 会判为无效并走 Side Output。

## 6.4 验证结果（看终端 A）

发送后回到终端 A，预期在 30 秒内看到：

- `A_ACCEPT_INVALID_20260518_NOT_JSON`

只要看到这行，就说明“无效数据 -> Side Output -> invalid_sensor_topic”链路已打通。

如果 30 秒内没看到，按下面顺序排查：

1. `OnlineInferenceJob` 日志里是否有 `enableInvalidSensorSink=true`（改完 `.env` 后要重启任务）。
2. 发送消息的 topic 是否和 `ZEPHYR_INPUT_TOPIC` 一致（不要发错 topic）。
3. `invalid_sensor_topic` 是否已创建。
4. `./kafka-console-consumer.sh` 和 `./kafka-console-producer.sh` 是否都在 `/export/server/kafka_2.12-2.4.1/bin` 执行。
5. `--bootstrap-server 192.168.88.161:9092` 是否是当前可达 Kafka 地址。

## 6.5 通过标准

1. `enableInvalidSensorSink=true` 时，非法输入能进入 `invalid_sensor_topic`
2. 关闭开关后（改回 `false` 并重启），新发送的非法消息不再进入 `invalid_sensor_topic`

## 6.6 反向验证（证明关闭开关后“确实不再写出”）

这一段非常关键，建议组长必须执行，避免误判。

### 6.6.1 先把开关关掉并重启

把 `.env` 改成：

```env
ZEPHYR_ENABLE_INVALID_SENSOR_SINK=false
```

然后重启全链路（至少要重启 `OnlineInferenceJob`）。

重启后在 `OnlineInferenceJob` 日志里确认：

- `enableInvalidSensorSink=false`

### 6.6.2 先发一个“基线合法消息”（用于确认链路还活着）

在输入 topic 发一条**合法 JSON**（字段齐全）：

```bash
echo '{"machineId":9991,"cycle":1,"pressure":21.1,"temperature":1578.2,"speed":140.3,"eventTime":1893456000000}' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

说明：合法消息会走主流程，不会进 `invalid_sensor_topic`，这一步是确认系统并非“整体停了”。

### 6.6.3 再发一个“非法消息”（本次要验证的对象）

```bash
echo 'A_ACCEPT_INVALID_20260518_NOT_JSON_AFTER_DISABLE' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

### 6.6.4 用“只看新消息”的方式消费 invalid topic

重点：这里不要用 `--from-beginning`，否则会把历史旧消息读出来，容易误以为“还在写”。

请新开终端，在发送完上面两条消息后立刻执行：

```bash
./kafka-console-consumer.sh --bootstrap-server 192.168.88.161:9092 --topic invalid_sensor_topic --timeout-ms 10000
```

预期：10 秒超时后直接退出，**不应出现**：

- `A_ACCEPT_INVALID_20260518_NOT_JSON_AFTER_DISABLE`

如果仍看到了这条新标记消息，说明关闭开关未生效，常见原因：

1. `.env` 虽改了，但 `OnlineInferenceJob` 没重启。
2. 重启的是旧窗口，实际跑的不是当前代码/当前配置。
3. 发消息的 topic 不是当前 `ZEPHYR_INPUT_TOPIC`。
4. 你用的是 `--from-beginning`，把历史消息当成了新消息。

---

## 7. 用例 A-3：验收 ONNX 后端切换与在线推理

## 7.1 生成 ONNX 模型（若已有可跳过）

在项目根目录 PowerShell 执行：

```powershell
cd .\zephyr-ml
python -m pip install skl2onnx onnx
python .\train\export_onnx.py --model-path .\models\best_risk_model.pkl --feature-columns-path .\models\feature_columns.json --output-path .\models\best_risk_model.onnx
cd ..
```

若 `best_risk_model.pkl` 缺失，可先运行：

```powershell
python .\zephyr-ml\tools\bootstrap_model.py
```

## 7.2 配置

`.env` 设置：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=onnx
ZEPHYR_ONNX_MODEL_PATH=D:\dashuju\homework\zephyr-watch\zephyr-ml\models\best_risk_model.onnx
ZEPHYR_ONNX_INPUT_NAME=input
ZEPHYR_ONNX_OUTPUT_NAME=
ZEPHYR_ONNX_JAVA_HOME=C:\Program Files\Java\jdk-21
ZEPHYR_FORCE_SYNC_REST_INFERENCE=false
```

> `ZEPHYR_ONNX_JAVA_HOME` 请改成你本机真实 JDK21 路径。

重启全链路。

## 7.3 观察日志

OnlineInferenceJob / OnlineInference 相关日志应出现：

- `riskInferenceBackend=onnx`
- `ZEPHYR_ONNX_BACKEND_ACTIVE modelPath=...`
- `MultiBackendRiskPredictFunction ONNX initialized: input=...`

如果出现：

- `ONNX unavailable, bridging to REST`

说明 ONNX 没成功，实际走了 REST 桥接，此用例判不通过。

## 7.4 MySQL 验证是否真的 ONNX 出数

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：出现 `...-onnx` 后缀的 `model_version`。

## 7.5 通过标准

1. 日志显示 ONNX 初始化成功
2. DB 中有 `-onnx` 的推理记录
3. 无持续 ONNX 初始化失败/桥接 REST 日志

---

## 8. 用例 A-4：验收 TF Serving 后端切换与在线推理

## 8.1 生成 TF Serving SavedModel

```powershell
cd .\zephyr-ml
python -m pip install tensorflow
python .\train\export_tf_serving_saved_model.py --model-path .\models\best_risk_model.pkl --feature-columns-path .\models\feature_columns.json --export-root .\models\tf_serving --model-name risk_classifier --version 1 --input-name inputs
cd ..
```

生成目录应存在：

- `zephyr-ml\models\tf_serving\risk_classifier\1\`

## 8.2 是否需要上传模型？

本验收文档按“**仅本机 Docker 部署 TF Serving**”执行：  
不需要上传模型到 node2，直接挂载本机导出的模型目录即可。

## 8.3 启动 TF Serving（本机 Docker）

PowerShell 执行（路径按实际调整）：

```powershell
docker run --rm -p 8501:8501 --name zephyr-tfs -v D:/dashuju/homework/zephyr-watch/zephyr-ml/models/tf_serving/risk_classifier:/models/risk_classifier -e MODEL_NAME=risk_classifier tensorflow/serving
```

另开一个终端检查：

```powershell
curl.exe -s http://localhost:8501/v1/models/risk_classifier
```

预期返回模型状态信息（如 `AVAILABLE`）。

## 8.4 配置（本机 Docker）

`.env` 设置：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=tf-serving
ZEPHYR_TF_SERVING_URL=http://localhost:8501/v1/models/risk_classifier:predict
ZEPHYR_TF_SERVING_INPUT_NAME=inputs
ZEPHYR_TF_SERVING_SIGNATURE_NAME=serving_default
ZEPHYR_FORCE_SYNC_REST_INFERENCE=false
```

重启全链路。

## 8.5 日志与出数验证

日志应至少看到：

- `riskInferenceBackend=tf-serving`

MySQL 验证：

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：出现 `...-tf-serving` 的 `model_version`。

## 8.6 通过标准

1. TF Serving 可联通并参与推理
2. DB 有 `-tf-serving` 推理结果
3. 无持续 `TF Serving URL is empty` 或响应解析失败错误

---

## 9. 回归验收：确认不影响其他成员功能

这是“能否合并”的关键步骤，必须做。

## 9.1 恢复团队默认配置（推荐）

`.env` 改回：

```env
ZEPHYR_RISK_INFERENCE_BACKEND=rest
ZEPHYR_FORCE_SYNC_REST_INFERENCE=false
ZEPHYR_ENABLE_INVALID_SENSOR_SINK=false
```

重启全链路。

## 9.2 检查 5 个主进程都启动

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'OnlineInferenceJob|RecommendJob|AlertReviewJob|SensorDataProducer|spring-boot-maven-plugin' } | Select-Object ProcessId, Name, CommandLine
```

预期：能看到对应进程。

## 9.3 检查 API 与 Dashboard

```powershell
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/api/dashboard/overview
```

预期：健康正常，overview 返回统计数据。

## 9.4 检查核心业务表持续更新

```sql
SELECT COUNT(*) AS pred_5m
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 5 MINUTE;

SELECT COUNT(*) AS alert_10m
FROM alert_event
WHERE created_at >= NOW() - INTERVAL 10 MINUTE;

SELECT COUNT(*) AS feature_5m
FROM online_feature_snapshot
WHERE created_at >= NOW() - INTERVAL 5 MINUTE;
```

预期：`pred_5m > 0`、`feature_5m > 0`；`alert_10m` 允许为 0（取决于风险触发情况）。

## 9.5 回归通过标准

1. 默认配置下所有既有流程可启动可运行
2. 业务主表持续写入
3. 无因 A 改造导致的启动失败/链路中断

---

## 10. 验收记录模板（建议按此提交给组长）

请把每个用例记录成以下格式：

```text
用例编号：A-1
执行时间：2026-05-18 14:30
执行人：XXX
配置快照：.env 中关键变量截图
关键日志：riskInferenceBackend=rest, useAsyncRestMainline=true
SQL结果：device_risk_prediction 5分钟新增 128 条
结论：通过
```

建议至少提供以下证据截图：

1. `.env` 关键配置（按当前用例）
2. OnlineInferenceJob 关键日志
3. API 健康检查返回
4. MySQL 查询结果
5. `invalid_sensor_topic` 消费结果（A-2）
6. ONNX / TF Serving 模型版本查询结果（A-3/A-4）

---

## 11. 常见失败与排查

1. ONNX 模式日志出现 `ONNX unavailable, bridging to REST`  
原因：ONNX 模型路径/JDK21/onnxruntime 依赖不匹配。  
处理：核对 `ZEPHYR_ONNX_MODEL_PATH`、`ZEPHYR_ONNX_JAVA_HOME`，确认模型文件存在。

2. TF Serving 模式无结果  
原因：容器未启动或 URL 配错。  
处理：先 `curl http://localhost:8501/v1/models/risk_classifier`，确认模型 `AVAILABLE`。

3. `invalid_sensor_topic` 无消息  
原因：未开 `ZEPHYR_ENABLE_INVALID_SENSOR_SINK` 或发送的并非无效消息。  
处理：确认开关为 `true`，发送明显非法字符串（非 JSON）。

4. 没有推理数据写入 MySQL  
原因：Kafka 输入无数据、MySQL 连接错误、任务未启动。  
处理：检查 `SensorDataProducer` 窗口是否持续发送，检查 API 与 OnlineInferenceJob 窗口报错。

---

## 12. 最终验收结论规则

仅当 A-1、A-1R、A-2、A-3、A-4、回归验收全部通过，才可认定：

**A 成员任务“在线推理增强”完成且交付可验收。**
