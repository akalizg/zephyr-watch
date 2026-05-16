# 支线 B 拓展功能开发分工

本文只覆盖支线 B 中尚未完成的拓展功能，不包含推荐系统升级，也不包含测试专项。

## 未完成功能范围

1. ONNX Runtime 在线推理接入。
2. TF Serving 推理接入。
3. 企业微信/钉钉 Webhook 真实适配。
4. Prometheus 系统级监控采集闭环补齐。
5. 模型灰度发布与自动回滚。
6. 人工反馈在线学习闭环增强。
7. 生产级自动调度与热加载增强。
8. Flink Batch 触发训练链路。
9. Flink Async I/O REST 推理。
10. 无效传感器数据落 Kafka 链路。

## 三人独立分工

### A：在线推理增强负责人

职责范围：

- 接入 ONNX Runtime 在线推理。
- 接入 TF Serving 推理链路。
- 将当前同步 REST 推理改造成 Flink Async I/O。
- 将无效传感器数据 Side Output 接入 `invalid_sensor_topic`。

主要涉及目录：

- `zephyr-flink-job/src/main/java/com/zephyr/watch/flink/process`
- `zephyr-flink-job/src/main/java/com/zephyr/watch/flink/app/OnlineInferenceJob.java`
- `zephyr-ml/train`
- `zephyr-ml/serve`
- `zephyr-common/src/main/java/com/zephyr/watch/common/constants`

交付物：

- ONNX 模型导出脚本或训练后导出步骤。
- ONNX 推理函数，输入输出字段与 `FeatureVector`、`RiskPrediction` 对齐。
- TF Serving 调用客户端，支持通过环境变量切换 REST / ONNX / TF Serving。
- Flink Async I/O 风险推理实现，避免模型服务抖动阻塞主流。
- OnlineInferenceJob 将 `ParseAndValidateSensorProcessFunction.INVALID_SENSOR_TAG` 接到 `KafkaJsonSinkFactory.buildInvalidSensorSink()`。
- README 中对应运行参数和架构说明更新。

边界：

- 不改推荐系统逻辑。
- 不负责 Prometheus Dashboard。

### B：模型发布与自动化负责人

职责范围：

- 完成模型灰度发布。
- 完成自动回滚。
- 增强人工反馈在线学习闭环。
- 增强增量训练调度和风险模型服务热加载。
- 补齐 Flink Batch 触发训练链路。

主要涉及目录：

- `zephyr-api/src/main/java/com/zephyr/watch/api`
- `zephyr-api/src/main/java/com/zephyr/watch/api/repository`
- `zephyr-api/src/main/java/com/zephyr/watch/api/service`
- `zephyr-ml/train/incremental_retrain.py`
- `zephyr-ml/train/register_model.py`
- `zephyr-ml/serve/risk_model_service.py`
- 数据库初始化 SQL 或建表脚本所在目录

交付物：

- `model_registry` 扩展字段设计，例如发布阶段、流量比例、回滚来源版本、健康状态。
- 候选模型注册、灰度激活、全量激活、回滚接口。
- 风险模型服务支持按版本加载模型，并记录当前加载版本。
- 人工审核、`review_label_topic`、`review_label_feedback`、`feedback_training_sample`、`incremental_retrain.py`、模型注册激活之间形成可说明的闭环。
- 增量训练调度状态持久化，包括最近运行时间、结果、失败原因。
- 明确当前采用“反馈样本合并后周期重训”，不要误写成已经完成 `partial_fit` 式真正在线微调。
- Flink Batch 或等价批任务入口，能够读取反馈样本并触发 `incremental_retrain.py` 或训练服务。
- 自动回滚判定规则，例如健康检查失败、连续推理失败、关键指标劣化。
- README 中模型发布、热加载和回滚说明更新。

边界：

- 不改 Flink Async I/O 和 ONNX/TF Serving 推理实现。
- 不做推荐系统升级。
- 不做测试专项脚本。

### C：系统监控可观测负责人

职责范围：

- 完成企业微信/钉钉 Webhook 真实适配。
- 补齐 Prometheus 系统级监控真实采集闭环。

主要涉及目录：

- `docs`
- `zephyr-api/src/main/java/com/zephyr/watch/api/WebhookConfigController.java`
- `zephyr-flink-job/src/main/java/com/zephyr/watch/flink/sink/WebhookAlertSink.java`
- Grafana Dashboard JSON 所在目录
- Spring Boot `application.yml`
- Flink/Kafka/JVM/主机指标相关的 Prometheus 配置文件新增目录

交付物：

- 企业微信/钉钉消息体格式适配，不能只发送通用 JSON。
- Webhook 签名密钥、超时、重试次数和最小风险等级配置。
- Webhook 发送失败记录和发送状态查询说明。
- Prometheus 抓取配置与实际 exporter 对齐，覆盖 Spring Boot、JVM、Flink、Kafka 和主机指标中可落地的部分。
- Grafana 系统监控 Dashboard JSON 已有初版，需要根据真实指标名修正并补充导入说明。
- README 中 Webhook 配置、监控入口、指标来源和 Dashboard 导入说明更新。

边界：

- 不负责模型灰度发布逻辑。
- 不负责 ONNX/TF Serving 推理代码。
- 不负责推荐系统和测试专项。

## 并行开发约定

- A 主要改 Flink 推理和模型服务推理接口，B 主要改模型注册/发布/热加载，C 主要改部署和监控，三者写入范围尽量不重叠。
- 共享环境变量命名统一使用 `ZEPHYR_` 前缀。
- 对外接口新增字段时优先保持向后兼容，避免影响现有 `run-all.bat` 全链路演示。
- README 只记录最终用户需要知道的运行方式和当前状态，详细任务拆分保留在本文档。
