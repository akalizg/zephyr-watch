# A成员功能验收手册（零基础可执行版）

> 这份手册是给“对项目几乎不了解的人”使用的。
> 你不需要理解代码，只需要按顺序执行命令，观察指定结果，即可完成验收。

---

## 1. 这份手册要验收什么

A 成员负责的是“在线推理增强”这一部分，一共验收 4 项能力：

1. 默认情况下，系统是否已经把 REST 推理主链路改成了 Async I/O。
2. 出现问题时，是否可以一键回退到同步 REST 推理。
3. 是否可以切换到 ONNX 后端并正常推理。
4. 是否可以切换到 TF Serving 后端并正常推理。
5. 遇到非法传感器数据时，是否可以把这类无效数据单独旁路到 `invalid_sensor_topic`。

最后还要确认一件事：

6. A 成员的改动不能把其他成员的主流程带坏。

---

## 2. 先认识 4 个你会用到的东西

为了避免后面看不懂，这里先用最直白的话解释一下。

### 2.1 `run-all.bat` 是什么

它是“全项目一键启动脚本”。

你可以把它理解成：

- 第一次启动项目时，运行它。
- 它会把这个项目需要的几个主要进程都拉起来。

### 2.2 `verify-task-a.ps1` 是什么

它是“A 成员专用的快捷验收脚本”。

你可以把它理解成：

- 它不会改你的 `.env` 文件。
- 它会临时切换 A 成员相关配置。
- 它只重启 `OnlineInferenceJob`，不会把整个项目都重启一遍。

所以，正式验收时的推荐方式是：

1. 先用 `run-all.bat` 启动一次全链路。
2. 后面每个 A 用例都用 `verify-task-a.ps1` 切换场景。

### 2.3 `logs\task-a\` 是什么

这是快捷验收脚本保存日志的目录。

你可以把它理解成：

- 每次运行 `verify-task-a.ps1`，它都会生成一份新的日志。
- 你不需要盯着黑窗口找日志，直接看这个目录里最新的日志文件就行。

### 2.4 MySQL 和 Kafka 在这里分别干什么

- MySQL：用来确认系统是不是“真的跑出了结果”。
- Kafka：用来确认“无效数据旁路”这条链路是不是真的通了。

如果你只想记住一句话：

- 看 MySQL，是为了验证“有没有推理结果”。
- 看 Kafka，是为了验证“非法消息有没有被单独送出去”。

---

## 3. 验收前准备

这一段只需要做一次。

### 3.1 进入项目根目录

```powershell
cd D:\dashuju\homework\zephyr-watch
```

### 3.2 确认关键文件存在

请确认项目根目录下至少有这些文件：

- `run-all.bat`
- `verify-task-a.ps1`
- `stop-zephyr.ps1`
- `.env`

还要确认这些文件存在：

- `zephyr-ml/train/export_onnx.py`
- `zephyr-ml/train/export_tf_serving_saved_model.py`

如果 `.env` 不存在，就执行：

```powershell
Copy-Item .env.example .env
```

### 3.3 确认基础软件存在

在 PowerShell 执行：

```powershell
where.exe java
where.exe mvn
```

如果这两个命令都能返回路径，说明 Java 和 Maven 基本可用。

### 3.4 确认外部依赖可用

这几个服务必须已经准备好：

1. Kafka
2. MySQL
3. Redis
4. Python 环境

如果要验收 TF Serving，还建议有 Docker。

### 3.5 确认 Kafka 里有需要的 topic

在 Kafka 所在机器执行：

```bash
cd /export/server/kafka_2.12-2.4.1/bin
./kafka-topics.sh --list --bootstrap-server 192.168.88.161:9092
```

至少应该能看到：

- `iot_sensor_data`
- `risk_prediction_topic`
- `alert_event_topic`
- `review_label_topic`
- `invalid_sensor_topic`

如果没有 `invalid_sensor_topic`，创建它：

```bash
./kafka-topics.sh --create --topic invalid_sensor_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
```

### 3.6 确认 MySQL 有业务表

至少确认以下表存在：

- `device_risk_prediction`
- `alert_event`
- `online_feature_snapshot`
- `maintenance_recommendation`

如果不确定数据库是否初始化过，可以重新执行：

- `zephyr-flink-job/src/main/resources/sql/mysql_init.sql`

---

## 4. 正式验收前的统一启动方式

这部分也只做一次。

### 4.1 先停掉历史进程

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-zephyr.ps1
```

这一步的作用是清理旧进程，避免你看到的是上一次留下来的结果。

### 4.2 启动全链路

```powershell
.\run-all.bat zephyr-flink-job\src\main\resources\models\model.pmml risk-classifier-rest-v1 true http://localhost:5001/api/risk/score
```

你可以把这一步理解成：“把项目主要流程统一拉起来”。

启动后，不要手动关闭这些窗口。

### 4.3 做一次健康检查

等 30 到 90 秒后，在 PowerShell 执行：

```powershell
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:5001/api/risk/health
curl.exe -s http://localhost:8080/api/dashboard/overview
```

看到下面这些现象，就说明基线启动基本成功：

1. 第一个接口返回里有 `"status":"UP"`
2. 第二个接口返回里有 `"success":true`
3. 第三个接口能返回 JSON 数据，不是报错页面

### 4.4 后面所有 A 用例都怎么切换

从这里开始，不再手改 `.env`，也不再每次重启整个项目。

统一使用下面这种命令切场景：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario 场景名
```

你只需要换最后的“场景名”。

日志统一在这里看：

```text
logs\task-a\
```

---

## 5. 用例 A-1：验证默认 REST 主链路已经是 Async I/O

这一项要证明：默认情况下，系统走的是“异步 REST 推理”，不是旧的同步方式。

### 5.1 执行命令

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario rest-async -ShowWindow
```

这条命令会自动做两件事：

1. 临时切到 A-1 对应的配置。
2. 只重启 `OnlineInferenceJob`。

### 5.2 你要看什么

看脚本输出，或者去 `logs\task-a\` 找最新日志。

必须看到下面 3 条关键信息：

- `riskInferenceBackend=rest`
- `useAsyncRestMainline=true`
- `forceSyncRestInference=false`

如果这 3 条都出现，说明默认 REST 主链路确实已经是 Async I/O。

### 5.3 再确认系统真的有输出结果

在 MySQL 客户端执行：

```sql
SELECT COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 5 MINUTE;
```

预期：`cnt > 0`

再执行：

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：能看到最近 10 分钟内有持续新增的记录。

### 5.4 这一项什么情况算通过

同时满足下面两条就算通过：

1. 日志明确出现 `useAsyncRestMainline=true`
2. MySQL 里最近几分钟有新的推理结果

---

## 6. 用例 A-1R：验证可以回退到同步 REST

这一项要证明：如果异步主链路要临时回退，也能正常跑。

### 6.1 执行命令

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario rest-sync -ShowWindow
```

### 6.2 你要看什么

看最新日志，应该出现：

- `useAsyncRestMainline=false`
- `ZEPHYR_FORCE_SYNC_REST_INFERENCE=true, fallback to sync REST inference.`

这表示系统已经从默认异步模式切回了同步 REST。

### 6.3 再确认回退后还能继续出结果

继续执行和 A-1 相同的 MySQL 查询：

```sql
SELECT COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 5 MINUTE;
```

预期：仍然大于 0。

### 6.4 这一项什么情况算通过

同时满足下面两条就算通过：

1. 日志确认已经回退到同步 REST
2. 回退后 MySQL 里仍然继续有新结果

---

## 7. 用例 A-2：验证无效传感器消息会被旁路到 `invalid_sensor_topic`

这一项要证明两件事：

1. 开启开关后，非法消息会进入 `invalid_sensor_topic`
2. 关闭开关后，新的非法消息不会再进去

### 7.1 第一步：开启旁路

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario invalid-on -ShowWindow
```

然后看最新日志，确认看到：

- `enableInvalidSensorSink=true`

### 7.2 第二步：发送一条“明显非法”的消息

这一步在 Kafka 所在机器上做。

先开终端 A，用来观察 `invalid_sensor_topic`：

```bash
cd /export/server/kafka_2.12-2.4.1/bin
./kafka-console-consumer.sh --bootstrap-server 192.168.88.161:9092 --topic invalid_sensor_topic --from-beginning --timeout-ms 30000
```

再开终端 B，向输入 topic 发送一条非法消息：

```bash
cd /export/server/kafka_2.12-2.4.1/bin
echo 'A_ACCEPT_INVALID_20260518_NOT_JSON' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

为什么这条消息算非法：

- 因为它不是 JSON
- 系统会把它识别为无效传感器数据

### 7.3 第三步：看是否真的被旁路

回到终端 A。

预期在 30 秒内能看到：

- `A_ACCEPT_INVALID_20260518_NOT_JSON`

看到这行，就说明这条无效消息被成功送到了 `invalid_sensor_topic`。

### 7.4 第四步：再验证关闭开关后不会继续写出

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario invalid-off -ShowWindow
```

看日志，确认出现：

- `enableInvalidSensorSink=false`

然后先发送一条合法消息，用来确认系统整体还在工作：

```bash
echo '{"machineId":9991,"cycle":1,"pressure":21.1,"temperature":1578.2,"speed":140.3,"eventTime":1893456000000}' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

再发送一条新的非法消息：

```bash
echo 'A_ACCEPT_INVALID_20260518_NOT_JSON_AFTER_DISABLE' | ./kafka-console-producer.sh --broker-list 192.168.88.161:9092 --topic iot_sensor_data
```

然后执行下面的消费命令：

```bash
./kafka-console-consumer.sh --bootstrap-server 192.168.88.161:9092 --topic invalid_sensor_topic --timeout-ms 10000
```

注意：

- 这里不要加 `--from-beginning`
- 否则你会把历史旧消息也看出来，容易误判

预期结果：

- 10 秒后命令超时退出
- 不应出现 `A_ACCEPT_INVALID_20260518_NOT_JSON_AFTER_DISABLE`

### 7.5 这一项什么情况算通过

同时满足下面两条就算通过：

1. 开启开关后，非法消息能进入 `invalid_sensor_topic`
2. 关闭开关后，新发的非法消息不再进入 `invalid_sensor_topic`

### 7.6 如果没通过，优先检查什么

按这个顺序排查：

1. 日志里是否真的显示 `enableInvalidSensorSink=true` 或 `false`
2. 发送消息的 topic 是否正确
3. `invalid_sensor_topic` 是否存在
4. Kafka 地址 `192.168.88.161:9092` 是否可达
5. 关闭开关后的验证是否误用了 `--from-beginning`

---

## 8. 用例 A-3：验证 ONNX 后端可以切换并正常推理

这一项要证明：系统不仅能切到 ONNX，而且切过去后还能真跑出结果。

### 8.1 如果 ONNX 模型还没生成，先生成

在 PowerShell 执行：

```powershell
cd .\zephyr-ml
python -m pip install skl2onnx onnx
python .\train\export_onnx.py --model-path .\models\best_risk_model.pkl --feature-columns-path .\models\feature_columns.json --output-path .\models\best_risk_model.onnx
cd ..
```

如果 `best_risk_model.pkl` 不存在，可以先执行：

```powershell
python .\zephyr-ml\tools\bootstrap_model.py
```

### 8.2 执行前先确认一件事

请确认 `.env` 中的 `ZEPHYR_ONNX_JAVA_HOME` 已经是正确的 JDK21 路径。

这是 ONNX 场景最容易出错的地方之一。

### 8.3 执行命令

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario onnx -ShowWindow
```

### 8.4 你要看什么

看最新日志，应该出现：

- `riskInferenceBackend=onnx`
- `ZEPHYR_ONNX_BACKEND_ACTIVE modelPath=...`
- `MultiBackendRiskPredictFunction ONNX initialized: input=...`

如果出现下面这句，就说明这次其实没有真正跑 ONNX，而是偷偷回退到 REST 了：

- `ONNX unavailable, bridging to REST`

只要看到这句，本用例就应该判定为不通过。

### 8.5 再确认 ONNX 确实产出了结果

执行：

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：能看到带 `onnx` 标记的 `model_version`。

### 8.6 这一项什么情况算通过

同时满足下面三条就算通过：

1. 日志显示 ONNX 初始化成功
2. 没有出现 `ONNX unavailable, bridging to REST`
3. MySQL 里有 ONNX 对应的推理结果

---

## 9. 用例 A-4：验证 TF Serving 后端可以切换并正常推理

这一项要证明：系统能调用本机 Docker 启动的 TF Serving。

### 9.1 如果 TF Serving 模型还没生成，先生成

```powershell
cd .\zephyr-ml
python -m pip install tensorflow
python .\train\export_tf_serving_saved_model.py --model-path .\models\best_risk_model.pkl --feature-columns-path .\models\feature_columns.json --export-root .\models\tf_serving --model-name risk_classifier --version 1 --input-name inputs
cd ..
```

生成后应存在目录：

- `zephyr-ml\models\tf_serving\risk_classifier\1\`

### 9.2 启动 TF Serving

在 PowerShell 执行：

```powershell
docker run --rm -p 8501:8501 --name zephyr-tfs -v D:/dashuju/homework/zephyr-watch/zephyr-ml/models/tf_serving/risk_classifier:/models/risk_classifier -e MODEL_NAME=risk_classifier tensorflow/serving
```

这个窗口先不要关。

再开一个新终端，执行：

```powershell
curl.exe -s http://localhost:8501/v1/models/risk_classifier
```

如果返回里能看到类似 `AVAILABLE` 的状态信息，说明 TF Serving 已经正常启动。

### 9.3 执行命令

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario tf-serving -ShowWindow
```

### 9.4 你要看什么

最新日志里至少应该看到：

- `riskInferenceBackend=tf-serving`

### 9.5 再确认 TF Serving 真的参与了推理

执行：

```sql
SELECT model_version, COUNT(*) AS cnt
FROM device_risk_prediction
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY model_version
ORDER BY cnt DESC;
```

预期：能看到带 `tf-serving` 标记的 `model_version`。

### 9.6 这一项什么情况算通过

同时满足下面三条就算通过：

1. TF Serving 服务本身可访问
2. 日志显示后端已经切到 `tf-serving`
3. MySQL 里有 TF Serving 对应的推理结果

---

## 10. 回归验收：确认 A 成员改动没有影响其他人

这是最后一步，也是非常关键的一步。

它要回答的问题是：

- A 这部分虽然能跑，但有没有把整个项目主流程带坏？

### 10.1 先恢复默认模式

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario default -ShowWindow
```

这一步的含义是：

- 把 A 相关场景切回默认模式
- 让项目回到团队平时演示时的常规状态

### 10.2 检查主进程是否都还在

执行：

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'OnlineInferenceJob|RecommendJob|AlertReviewJob|SensorDataProducer|spring-boot-maven-plugin' } | Select-Object ProcessId, Name, CommandLine
```

预期：能看到这些主要进程对应的信息。

### 10.3 检查 API 和 Dashboard 是否还能访问

执行：

```powershell
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/api/dashboard/overview
```

预期：

1. health 接口仍然返回 `"status":"UP"`
2. dashboard 接口仍然能返回 JSON 数据

### 10.4 检查核心业务表是否仍有新数据

执行：

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

预期：

- `pred_5m > 0`
- `feature_5m > 0`
- `alert_10m` 允许为 0，因为它和风险触发情况有关

### 10.5 这一项什么情况算通过

同时满足下面三条就算通过：

1. 默认模式下主要进程仍在运行
2. API 和 dashboard 仍能正常访问
3. 核心业务表仍然持续写入数据

---

## 11. 推荐的正式验收顺序

如果你是组长或老师，建议就按这个顺序做，不容易乱：

1. 先执行第 4 节，启动全链路基线。
2. 执行 A-1，验证默认 Async REST。
3. 执行 A-1R，验证同步 REST 回退。
4. 执行 A-2，验证无效消息旁路与关闭开关后的反向结果。
5. 执行 A-3，验证 ONNX。
6. 执行 A-4，验证 TF Serving。
7. 执行第 10 节，做回归验收。

---

## 12. 验收记录怎么写

建议每个用例都按下面格式记录：

```text
用例编号：A-1
执行时间：2026-05-18 14:30
执行人：XXX
执行命令：powershell -ExecutionPolicy Bypass -File .\verify-task-a.ps1 -Scenario rest-async
看到的关键日志：riskInferenceBackend=rest, useAsyncRestMainline=true
MySQL / Kafka 结果：device_risk_prediction 5分钟新增 128 条
结论：通过
```

建议保留这些截图或证据：

1. `verify-task-a.ps1` 的执行命令与输出
2. `logs\task-a\` 里对应场景的日志
3. API 健康检查结果
4. MySQL 查询结果
5. `invalid_sensor_topic` 的消费结果
6. ONNX / TF Serving 场景下的模型版本查询结果

---

## 13. 常见失败与排查

### 13.1 ONNX 日志出现 `ONNX unavailable, bridging to REST`

这表示：

- 这次并没有真正跑 ONNX
- 系统退回到了 REST

优先检查：

1. `ZEPHYR_ONNX_JAVA_HOME` 是否正确
2. ONNX 模型文件是否存在
3. 是否确实执行的是 `-Scenario onnx`

### 13.2 TF Serving 没有结果

优先检查：

1. Docker 容器是否真的启动了
2. `curl http://localhost:8501/v1/models/risk_classifier` 是否能看到 `AVAILABLE`
3. 是否执行的是 `-Scenario tf-serving`

### 13.3 `invalid_sensor_topic` 没有看到消息

优先检查：

1. 是否执行了 `-Scenario invalid-on`
2. 日志里是否真的有 `enableInvalidSensorSink=true`
3. 发出去的是否是明显非法的非 JSON 字符串
4. topic 名称是否正确

### 13.4 MySQL 一直没有新数据

优先检查：

1. `SensorDataProducer` 是否还在运行
2. API 是否健康
3. `OnlineInferenceJob` 最新日志里是否报错
4. MySQL 连接是否正常

### 13.5 切换场景后看起来没有生效

优先检查：

1. 是否真的执行了 `verify-task-a.ps1`
2. 是否看的就是 `logs\task-a\` 下最新时间戳的日志
3. 是否有旧窗口残留导致误判

---

## 14. 最终结论怎么判定

只有当下面这些全部通过时，才能认定 A 成员任务已完成并可验收：

1. A-1 通过
2. A-1R 通过
3. A-2 通过
4. A-3 通过
5. A-4 通过
6. 回归验收通过

最终结论可写为：

**A 成员任务“在线推理增强”完成，交付可验收。**
