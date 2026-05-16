# 企业微信 / 钉钉 Webhook 联调与验收演示说明

**文档性质**：面向组长或协作方的**书面验收脚本**，与 [`c-observability.md`](c-observability.md) 中的技术细节一致，侧重**演示顺序、验收口径与风险控制**。  
**版本说明**：以当前仓库 `zephyr-api` + `zephyr-flink-job` 行为为准；接口路径与字段语义以 `c-observability.md` 为准。

---

## 1. 演示目标

在可控前提下，证明以下能力已落地且可复现：

| 序号 | 能力 | 验收方式 |
|------|------|----------|
| 1 | **多通道消息体**与厂商协议对齐（非单一通用 JSON） | `dryRun=true` 对比 `bodyJson` 根结构 |
| 2 | **企业微信**、**钉钉**均可真连外呼成功 | 群内可见消息 + API 返回 `200` / `status: OK` |
| 3 | **可选加签**（钉钉等）与 **最小风险等级** 配置生效 | 列表中 `signSecretConfigured`；过滤行为符合 `min_risk_level` |
| 4 | **可观测**：成功 / 失败均可落库、可按 Webhook 分页查询 | 见 **§4.4**；演示步骤 **G** 展示 `deliveries` |

---

## 2. 范围与不在范围

**在范围内**

- 通过 Spring Boot API 登记 `webhook_config`、执行联调测试、查询投递日志。
- 说明「实时作业外呼」与「接口单条测试」两种触发源的区别（见 §6）。

**不在范围内**

- 不展开 Flink 作业内部算法与模型训练逻辑。
- 不要求答辩现场必须打通 Flink / Kafka（若仅验通道，可只跑 API 与数据库）。

---

## 3. 前置条件

1. **MySQL**：已存在 `webhook_config`、`webhook_send_log`；老库需按 [`c-observability.md`](c-observability.md) §1.2 完成升级脚本或等价迁移。
2. **zephyr-api**：已启动，JDBC 指向上述库；默认假设 Base URL 为 `http://localhost:8080`（以实际部署为准）。
3. **网络**：API 所在主机可 HTTPS 访问企业微信、钉钉开放平台域名（校园网需提前自测一次真实 `POST …/test`）。
4. **群与机器人**：建议使用**独立演示群**；企业微信使用「自定义消息推送」中的 Webhook URL；钉钉使用自定义机器人 URL。钉钉若启用 **加签**，须在配置中填写 `webhookSignSecret`（`SEC…` 整串）；**未启用加签则不得填写**，否则 URL 会被错误追加签名参数。
5. **保密**：Webhook URL、access_token、加签密钥**禁止**写入 Git、截图外发或粘贴到公共聊天；验收材料中一律使用占位符或脱敏描述。

从零安装环境：见 [`setup-collaborators.md`](setup-collaborators.md)。

---

## 4. 配置登记（建议用 Apifox / Postman）

以下请求 `Content-Type` 均为 `application/json`。

### 4.1 创建企业微信通道

`POST /api/webhooks`

```json
{
  "name": "验收-企业微信-演示群",
  "webhookType": "WECOM",
  "webhookUrl": "<企业微信 Webhook 完整 URL>",
  "minRiskLevel": "LOW",
  "enabled": true
}
```

未启用企业微信加签时，**不要**包含 `webhookSignSecret` 字段。

### 4.2 创建钉钉通道

`POST /api/webhooks`

```json
{
  "name": "验收-钉钉-演示群",
  "webhookType": "DINGTALK",
  "webhookUrl": "<钉钉 robot/send 完整 URL>",
  "webhookSignSecret": "<仅加签开启时填写 SEC… 整串，否则删除本字段>",
  "minRiskLevel": "LOW",
  "enabled": true
}
```

`min_risk_level` 在联调阶段设为 `LOW`，可避免联调用例中的告警等级被过滤（与 [`c-observability.md`](c-observability.md) 中测试探针等级一致）。

### 4.3 取得 `webhookId`

`GET /api/webhooks`

从返回列表中记录企业微信、钉钉两行各自的 **`webhookId`**，后续 `{id}` 均替换为对应数值。

### 4.4 发送失败记录与发送状态查询（交付物说明）

本小节对应工作规划中 **「Webhook 发送失败记录和发送状态查询说明」**：数据落在 MySQL，查询走 REST，与 Flink / API 双写路径一致。

#### 4.4.1 落库表 `webhook_send_log`

每次外呼结束（成功或耗尽重试）写入一行（`dryRun=true` **不写库**，因未发起 HTTP）。主要字段含义：

| 字段 | 含义 |
|------|------|
| `webhook_id` | 对应 `webhook_config.webhook_id`；**仅环境变量 URL** 外呼时可为 `NULL`（见下） |
| `webhook_type` | 通道类型快照，如 `WECOM`、`DINGTALK` |
| `source` | **`API`**：来自 `POST /api/webhooks/{id}/test`；**`FLINK`**：来自作业 `WebhookAlertSink` |
| `event_id` / `machine_id` / `risk_level` | 与当次 `AlertEvent` 一致，便于与业务告警关联 |
| `status` | **`SUCCESS`**：HTTP 成功且厂商返回视为成功（含对 `errcode` 的语义判断）；**`FAILED`**：网络异常、HTTP 4xx/5xx，或 2xx 但 `errcode != 0` 且重试仍失败 |
| `http_status` | HTTP 状态码；无 HTTP 层响应时可能为空 |
| `attempts` | 实际尝试次数（受 `ZEPHYR_WEBHOOK_MAX_ATTEMPTS` 上限约束） |
| `error_message` | 失败时简要原因（截断写入），成功时为空 |
| `created_at` | 写入时间 |

表结构见 `zephyr-flink-job/src/main/resources/sql/mysql_init.sql` 中 `CREATE TABLE webhook_send_log`。

#### 4.4.2 状态查询接口

`GET /api/webhooks/{id}/deliveries?page=0&size=20`

- **`{id}`**：必须与库中 `webhook_id` 一致；仅查询**该条** Webhook 的投递历史。
- **分页**：`page` 从 0 起；`size` 默认 20，**最大 100**；按 `log_id` **倒序**（最新在前）。
- **响应体**：`data` 内含 `items`（行数组）、`page`、`size`、`total`。每行字段名采用驼峰，例如 `logId`、`status`、`httpStatus`、`attempts`、`errorMessage`、`source`、`createdAt` 等。

**边界说明**

- **仅配置了 `ZEPHYR_ALERT_WEBHOOK_URL`、无库表行**：仍会尝试写 `webhook_send_log`，但 `webhook_id` 为 `NULL`，**不会**出现在上述按 `{id}` 查询的结果中；可依赖 Flink 标准错误输出中的 `ZEPHYR_WEBHOOK_FAIL|` 排查。
- **表不存在或写库失败**：主流程不中断；进程会打印 `ZEPHYR_WEBHOOK_LOG_FAIL|...`，此时接口侧可能无对应行，需先修库或执行升级脚本。

#### 4.4.3 验收时建议向组长口述的三句话

1. 每次真实推送（无论 Flink 还是 API）都会在 **`webhook_send_log`** 留痕，**成功 / 失败都有状态字段**。  
2. 通过 **`GET /api/webhooks/{id}/deliveries`** 可按 Webhook 分页拉历史，失败行看 **`error_message` 与 `attempts`** 即可对齐重试策略。  
3. 厂商 **HTTP 200 但 `errcode != 0`** 在本系统中按**失败**处理并重试，避免「假成功」。

---

## 5. 推荐演示脚本（约 8～12 分钟）

| 步骤 | 动作 | 说明 |
|------|------|------|
| A | `GET /api/webhooks` | 展示配置落库、列表**不返回**密钥，仅 `signSecretConfigured` 等元数据。 |
| B | 企业微信：`POST /api/webhooks/{企业微信id}/test?dryRun=true` | 可选 Query：`message=一号机组轴承温度联调演示`。展开响应 **`data.bodyJson`**，指出 `markdown.content` 结构。 |
| C | 钉钉：同上 URL 换为 `{钉钉id}` | 指出钉钉为 `markdown.title` + `markdown.text`，与企微**根级 JSON 形状不同**，体现「真实适配」而非改文案。 |
| D | （可选）`GENERIC` 类型再 `dryRun` 一次 | 与 `AlertEvent` 直出 JSON 对比，完成三通道对照。 |
| E | 企业微信：`POST /api/webhooks/{企业微信id}/test?message=一号机组轴承温度联调演示` | **真实外呼**；Body 可为空。群内应出现带 **「联调演示」** 标识的 Markdown（与实现一致）。 |
| F | 钉钉：同上换 `{钉钉id}` | 同上；响应 `module` 为 `webhook-config-test`、`status` 为 `OK` 即接口侧成功。 |
| G | `GET /api/webhooks/{id}/deliveries?page=0&size=20` | 对企微、钉钉各查一次。在 `items` 中指给组长看：`source`（`API` / `FLINK`）、`status`、`httpStatus`、`attempts`、`eventId`、`errorMessage`（若有失败行）、`total`。 |
| H | （可选）故意失败再查库 | 例如临时将某条 `webhookUrl` 改为无效域名并 `PUT` 更新，再 `POST …/test`，应出现 **`FAILED`** 行且 `error_message` 非空；**演示后立刻改回正确 URL**，避免污染生产群配置。 |

**口径**：步骤 B～D 证明**协议与拼装正确**；步骤 E～F 证明**网络与厂商侧接受**；步骤 G～H 证明**发送状态与失败记录可查**（§4.4）。

---

## 6. 常见现象：「企业微信先响、钉钉在步骤 E 之后才响」

两种触发源并存，**均属正常，且可一并视为验收通过**：

| 触发源 | 行为 | 群内消息特征 |
|--------|------|----------------|
| **Flink 作业** `WebhookAlertSink` | 读取库中 `enabled=1` 的配置，在**实时告警**产生时外呼 | 业务字段完整（如告警类型、模型版本、业务摘要等），**无** API 测试专用的 `WEBHOOK_TEST` 联调前缀 |
| **API** `POST /api/webhooks/{id}/test` | 仅向**该 id** 对应的一条配置发送 | 构造事件含 **`WEBHOOK_TEST`**，Markdown 中带 **「联调演示」** 说明 |

因此：若 Flink 已运行且企业微信通道已启用，**在手动执行步骤 E 之前**企业微信即可收到**流水线告警**；钉钉若仅在步骤 E/F 才调用 `test`，则钉钉在**该请求之后**才出现消息**完全符合设计**。答辩或验收时可用一句话概括：*「一条链路验证生产外呼，一条验证接口级联调；触发源不同，时间不必对齐。」*

若希望**仅**在步骤 E/F 才出现消息，可临时在库中将非演示通道 `enabled=0`，或暂停 Flink 告警外呼，避免与「按脚本点击」混淆。

---

## 7. 验收通过标准（建议组长勾选）

- [ ] 企业微信、钉钉各自至少完成一次 **`dryRun=true`**，且 `bodyJson` 与 [`c-observability.md`](c-observability.md) §1.1 描述一致。
- [ ] 企业微信、钉钉各自至少完成一次**无** `dryRun` 的 **`test`**，HTTP 成功且群内可见。
- [ ] `GET …/deliveries` 返回结构完整：`items`、`total`、`page`、`size`；至少一条 **`SUCCESS`** 记录，且能说明 `source` 含义（`API` 联调 vs `FLINK` 实时）。
- [ ] （可选加分）存在或现场构造一条 **`FAILED`** 记录，能结合 `attempts` 与 `error_message` 说明重试与失败语义（见 §4.4.3）。
- [ ] 演示全程未在材料中泄露完整 Webhook URL、token 或 `SEC` 密钥。

---

## 8. 安全与收尾

1. 演示结束后，建议在厂商控制台**轮换或作废**已暴露的 Webhook / 密钥，并删除或禁用测试用 `webhook_config` 行。  
2. 生产环境应结合 `min_risk_level`、群分级与告警降噪策略单独评审（本文不展开）。

---

## 9. 附录：相关接口索引

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/api/webhooks` | 列表 |
| `POST` | `/api/webhooks` | 创建 |
| `PUT` | `/api/webhooks/{id}` | 更新（含清空密钥 `clearWebhookSignSecret`） |
| `POST` | `/api/webhooks/{id}/test` | 真实外呼；Query `dryRun=true` 仅验消息体 |
| `GET` | `/api/webhooks/{id}/deliveries` | 投递分页查询 |

超时、重试等运行时参数：见 [`c-observability.md`](c-observability.md) §1.3。
