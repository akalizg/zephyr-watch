# 任务 C：Webhook 与系统监控（可观测）

本文与 `branch-b-extension-workplan.md` 中 **C：系统监控可观测负责人** 的交付物对齐，并说明当前仓库实现状态与后续补齐项。

**协作成员从零跑通（含 MySQL、Kafka、Grafana Compose、`.env`）**：见 [`setup-collaborators.md`](setup-collaborators.md)。

## 1. Webhook（企业微信 / 钉钉）

### 1.1 `webhook_config.webhook_type`

| 取值（不区分大小写） | 行为 |
|---------------------|------|
| `GENERIC`（默认） | 请求体为 `AlertEvent` 的 JSON（与历史行为一致）。 |
| `WECOM`、`WEWORK`、`WECHAT_WORK`、`QYWX` | 企业微信群机器人 **markdown** 消息体：`msgtype` + `markdown.content`。 |
| `DINGTALK`、`DING` | 钉钉自定义机器人 **markdown** 消息体：`msgtype` + `markdown.title` + `markdown.text`。 |

Flink 侧 `WebhookAlertSink` 与 API `POST /api/webhooks/{id}/test` 使用同一套字段拼装逻辑（Flink 与 API 各有一份 `WebhookChannelBodies`，避免模块循环依赖）。

### 1.2 加签密钥（`webhook_sign_secret`）

当机器人在控制台开启 **加签** 时，将密钥写入 `webhook_config.webhook_sign_secret`（通过创建/更新 Webhook 的 JSON 字段 `webhookSignSecret`）。Flink 与 API 外呼前会按 **企业微信 / 钉钉** 通用规则在 URL 上追加 `timestamp` 与 `sign`（HMAC-SHA256 + Base64 + URL 编码）。

- **列表接口**不返回密钥，仅返回 `signSecretConfigured`（0/1）表示是否已配置。
- **更新**：可设 `webhookSignSecret` 为新值；或设 `clearWebhookSignSecret: true` 清空密钥（二者互斥）。
- **仅环境变量 URL**（`ZEPHYR_ALERT_WEBHOOK_URL`）：可选 `ZEPHYR_ALERT_WEBHOOK_SIGN_SECRET` 作为该条目标的加签密钥（不落库）。

**已有库升级**：执行一次 `zephyr-flink-job/src/main/resources/sql/mysql_webhook_task_c_upgrade.sql`（或在新环境直接使用已更新的 `mysql_init.sql`）。

### 1.3 环境变量（统一 `ZEPHYR_` 前缀）

| 变量 | 含义 | 默认 |
|------|------|------|
| `ZEPHYR_WEBHOOK_CONNECT_TIMEOUT_MS` | HTTP 连接超时 | 5000 |
| `ZEPHYR_WEBHOOK_READ_TIMEOUT_MS` | HTTP 读超时 | 5000 |
| `ZEPHYR_WEBHOOK_MAX_ATTEMPTS` | 最大尝试次数（含首次） | 2 |
| `ZEPHYR_ALERT_WEBHOOK_SIGN_SECRET` | 与 `ZEPHYR_ALERT_WEBHOOK_URL` 配套的加签密钥（可选） | 空 |
| `ZEPHYR_ALERT_WEBHOOK_TYPE` | 仅配置环境变量 URL、无库表行时，等价于 `webhook_config.webhook_type`（`GENERIC` / `WECOM` / `DINGTALK` 及 §1.1 别名） | `GENERIC` |
| `ZEPHYR_ALERT_WEBHOOK_MIN_RISK_LEVEL` | 仅 URL 模式时等价于 `webhook_config.min_risk_level` | `HIGH` |

表字段 **`min_risk_level`** 仍为最小风险等级过滤（与既有逻辑一致）；多行 `webhook_config` 仍按各行独立过滤。

Flink 任务与 Spring Boot API 进程均需各自能读到上述环境变量（例如 `run-all.bat` 启动的窗口、或 IDE Run Configuration）。

### 1.4 测试接口与 `dryRun`

- **真实外呼**：`POST /api/webhooks/{id}/test`（不向公网发包时可能因网络/防火墙出现 502，属环境限制而非 JSON 拼错）。自定义摘要：**Query** `message=今晚7点吃饭`，或 **JSON Body** `{"message":"今晚7点吃饭"}`（`Content-Type: application/json`）；二者都传时优先使用 Query。不传则使用内置**联调演示**摘要（中文场景说明，便于答辩一眼看懂在验什么）。
- **仅验证消息体（推荐在校园网/无外网时使用）**：`POST /api/webhooks/{id}/test?dryRun=true`，返回 `bodyUtf8`、**`bodyJson`（树形，便于 Postman 并排对比）**、`webhookType`、`normalizedChannel`、`channelDemoTitle`、`howToExplainInDefense`（给老师的一句话口径）、`diffVsGenericJson`（与 `GENERIC` 根级 JSON 的差异说明）、`postmanTip`（答辩顺序建议）、`signSecretConfigured`、`requestUrlForDisplay`（`sign` 已打码），不发起 HTTP 出站。同样需要自定义摘要时可加 `message=…`（与 `dryRun` 可同时使用）。
- **书面化验收脚本（组长 / 协作方）**：演示顺序、验收勾选表与「Flink 实时外呼 vs API 测试」说明见 [`webhook-channel-demo-runbook.md`](webhook-channel-demo-runbook.md)。

**答辩演示建议（Postman）**

1. 准备两条 Webhook 配置（或同一 URL 下先后改 `webhook_type`）：`DINGTALK` 与 `WECOM`（或别名 `QYWX` 等，见 §1.1）。
2. 对两者各调用一次 `…/test?dryRun=true`，在响应 **`data.bodyJson`** 中并排展示：钉钉含 `markdown.title` + `markdown.text`；企业微信为 `markdown.content` 单行结构——**顶层 JSON 形状不同**，即「不是只改字符串的同一格式」。
3. 再调用一次 `GENERIC` 的 `dryRun`，对比根级 `AlertEvent` 字段与厂商 `msgtype` 封装。
4. 最后再发 **一次** 真实 `test`（无 `dryRun`）：群内展示带 **「联调演示」** 与 **「一号机组…模拟」** 的 Markdown，避免老师误以为是现场乱报故障；重复点击会重复推送，答辩以步骤 2～3 为主即可。

`application.yml` 已配置 `server.error.include-message: always`，502 响应体中的 `message` 字段会包含简要原因。

### 1.5 发送记录与状态查询

#### 落库：`webhook_send_log`

Flink `WebhookAlertSink` 与 API `POST /api/webhooks/{id}/test` 在每次**真实外呼**结束（成功或重试耗尽）后各写一行；**`dryRun=true` 不写库**（未发起 HTTP）。

| 字段 | 说明 |
|------|------|
| `webhook_id` | 对应 `webhook_config`；仅 `ZEPHYR_ALERT_WEBHOOK_URL` 模式可为 `NULL` |
| `webhook_type` | 通道快照，如 `WECOM`、`DINGTALK` |
| `source` | `FLINK`（作业 Sink）或 `API`（测试接口） |
| `event_id` / `machine_id` / `risk_level` | 与当次告警事件一致 |
| `status` | `SUCCESS` 或 `FAILED` |
| `http_status` | HTTP 状态码，无响应时可为空 |
| `attempts` | 实际尝试次数（受 `ZEPHYR_WEBHOOK_MAX_ATTEMPTS` 限制） |
| `error_message` | 失败时简要原因（截断）；成功为空 |
| `created_at` | 写入时间 |

建表 DDL：`zephyr-flink-job/src/main/resources/sql/mysql_init.sql`（`webhook_send_log`）。

#### 查询：`GET /api/webhooks/{id}/deliveries`

- **路径参数** `{id}`：必须为库中 `webhook_id`；按该条 Webhook **分页**返回投递历史。
- **Query**：`page`（默认 0）、`size`（默认 20，**最大 100**）；按 `log_id` **倒序**。
- **响应**：`data.items` 为行列表，`data.total` / `data.page` / `data.size` 为分页元数据；行内字段为驼峰，如 `logId`、`httpStatus`、`errorMessage`、`source` 等。

#### 边界与排障

- **仅环境变量 Webhook**（无 `webhook_id`）：仍会尝试写 `webhook_send_log`，但 `webhook_id` 为 `NULL`，**不会**出现在按 `{id}` 的 `deliveries` 结果中；可结合 Flink 日志 `ZEPHYR_WEBHOOK_FAIL|` 排查。
- **表未创建或写库失败**：主流程不中断；打印 `ZEPHYR_WEBHOOK_LOG_FAIL|...`，需修库或执行 [`mysql_webhook_task_c_upgrade.sql`](../zephyr-flink-job/src/main/resources/sql/mysql_webhook_task_c_upgrade.sql)。

HTTP **2xx** 但响应 JSON 中 **`errcode != 0`**（钉钉 / 企业微信常见）会视为失败并重试（在 `ZEPHYR_WEBHOOK_MAX_ATTEMPTS` 内），与纯 HTTP 状态码判断相比更接近真实机器人语义。

**面向验收的书面演示顺序**（含失败记录演示建议）：[`webhook-channel-demo-runbook.md`](webhook-channel-demo-runbook.md) §4.4、§5 步骤 G～H。

## 2. Prometheus 与 Grafana

- **抓取配置**：`zephyr-dashboard/prometheus/prometheus.yml`（含全局 `scrape_timeout`，各 job 的 `metrics_path` 与 exporter 常见约定对齐）。
- **Exporter / 指标名说明**：`zephyr-dashboard/prometheus/exporter-notes/README.md`（含 Spring Boot 2.7 + Micrometer 常见序列名：`jvm_memory_used_bytes`、`http_server_requests_seconds_count`、`process_cpu_usage` 等）。
- **系统大盘**：`zephyr-dashboard/grafana/dashboards/zephyr-watch-system.json`（`up{job=...}`、API JVM/HTTP/CPU、Flink/Kafka 可选面板；Flink/Kafka 无 exporter 时部分图为空属正常）。
- **看板加载**：`docker-compose.grafana.yml` 挂载 `grafana/dashboards` 并由 `grafana/provisioning/dashboards/zephyr.yml` 做 file provisioning，启动后 Grafana 侧 **Zephyr Watch** 文件夹内自动出现业务盘与系统盘（无需每次手动 Import，除非使用未挂卷的旧 `docker run` 命令）。
- **数据源 UID**：预置文件 `grafana/provisioning/datasources/*.yml` 中已固定 `uid: ZephyrMySQL` / `uid: ZephyrPrometheus`，与上述 Dashboard JSON 一致。
- **一键说明与 Docker 示例**：`zephyr-dashboard/README.md`；业务看板 SQL 与布局仍见 `docs/p1/grafana-dashboard-guide.md`。

建议流程：先保证 **Targets 中 `zephyr-api` 为 UP**（仅需本机 API），再按需部署 Flink / Kafka / node_exporter 并修改 `prometheus.yml` 中的 `targets`；若实际指标前缀与大盘不同，在 Prometheus **Graph** 中验证后再改 `expr`。

## 3. README 入口

项目根 `README.md` 中「任务 C：可观测性与 Webhook」小节链接到本文，便于答辩与交接时对照交付物。
