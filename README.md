# Zephyr-Watch：基于 Flink 与机器学习的工业设备实时预警与智能维护推荐平台
Zephyr-Watch 是一套面向工业设备预测性维护场景的实时智能预警平台。项目基于 NASA C-MAPSS 涡扇发动机数据集，构建从传感器数据接入、Flink 实时特征工程、HDFS/Hive 离线特征仓库、Python 机器学习建模、Flink 在线推理、异常告警、Grafana 可视化到智能维护推荐的完整闭环。

本项目不再局限于单一 RUL 剩余寿命预测，而是进一步扩展为：

- 实时设备状态监测；
- RUL 剩余寿命预测；
- 故障风险分类；
- 高风险异常告警；
- 人工审核标签回流；
- 周期性模型更新；
- 智能维修策略推荐；
- Grafana 业务监控与系统监控。

## 一、 项目背景与业务逻辑

### 1. 为什么做这个项目？
在重型工业、航空发动机、制造设备等场景中，传统维护方式主要包括两类：

1. **定期维护**：按照固定周期检修，容易造成过度维护和资源浪费；
2. **事后维护**：设备故障后再维修，容易导致停机损失和安全风险。

Zephyr-Watch 通过实时处理设备传感器数据，在设备真正故障前识别风险趋势，提前给出剩余寿命预测、异常告警和维修建议，从而支撑预测性维护。

### 2. 使用数据集：NASA C-MAPSS

本项目使用 NASA C-MAPSS 涡扇发动机退化数据集。

数据含义：

- 每台发动机从健康状态运行到故障状态；
- 每条记录表示某台发动机在某个运行周期下的传感器快照；
- 数据包括机器编号、运行周期、运行设置和多个传感器值；
- 适合用于 RUL 预测、故障风险识别和设备健康状态建模。

核心挑战：

- 原始传感器数据存在噪声；
- 单点数据难以准确反映设备健康状态；
- 需要通过窗口统计、序列特征和趋势特征提取设备退化模式；
- 高风险样本数量远少于正常样本，属于典型不平衡分类问题。

---
## 🏗️ 二、 技术栈与架构走向

### 1. 技术栈
| 组件 | 推荐技术 | 在本项目中的作用 |
|---|---|---|
| 流处理引擎 | Apache Flink 1.17+ | 实时清洗、窗口计算、CEP、在线推理 |
| 消息队列 | Kafka / Pulsar | 接入传感器流、告警流、审核回流流 |
| ML 框架 | Scikit-learn / XGBoost / LightGBM / PyTorch / TensorFlow | 风险识别、异常检测、推荐模型训练 |
| 模型部署 | PMML / ONNX / Flink UDF / REST / TF Serving | 将离线训练模型部署到实时推理链路 |
| 离线存储 | HDFS | 存储清洗数据和窗口特征数据 |
| 数仓映射 | Hive | 将 HDFS 文件映射为外部表，供 Python SQL 查询 |
| 结果存储 | MySQL | 保存预测结果、告警事件、审核标签和推荐结果 |
| 特征缓存 | Redis | 缓存实时设备状态、风险分数和推荐结果 |
| 模型仓库 | MinIO | 保存 PMML、ONNX、PKL、阈值文件和模型元数据 |
| 监控展示 | Prometheus + Grafana | 展示 Flink 作业指标、业务指标、告警趋势和推荐结果 |
| 告警通知 | 企业微信 / 钉钉 Webhook | 实时推送高风险设备告警 |
| 推荐系统 | KNN / Two-Tower / LightGCN / SASRec | 推荐维修策略、备件和相似故障案例 |

## 三、项目总体目标

本项目的最终目标是构建一套完整的工业设备智能运维系统。

系统能力包括：

| 模块 | 目标 |
|---|---|
| 实时数据接入 | 使用 Kafka 接入设备传感器流 |
| 实时流处理 | 使用 Apache Flink 进行高吞吐、低延迟计算 |
| 实时特征工程 | 使用滑动窗口统计、序列特征和趋势特征 |
| 离线特征仓库 | 使用 HDFS 存储特征文件，Hive 建立外部表映射 |
| 机器学习建模 | 使用 Scikit-learn、XGBoost、LightGBM 等训练风险识别模型 |
| 在线推理 | Flink 加载 PMML/ONNX 模型或通过 REST 调用模型服务 |
| 异常告警 | 高风险事件写入 Kafka、MySQL，并推送企业微信/钉钉 |
| 在线学习 | 人工审核标签回流，周期性更新模型 |
| 推荐系统 | 根据高风险设备状态推荐维修策略、备件和相似案例 |
| 可视化展示 | 使用 Prometheus + Grafana 展示系统指标和业务结果 |

## 🛠️ 四、 运行原项目完整操作指南
### 0. 环境准备
```bash

vi /export/server/kafka_2.12-2.4.1/config/server.properties
# 实际干活的：监听所有网卡的请求（最包容、最稳妥）
listeners=PLAINTEXT://0.0.0.0:9092

# 对外宣称的：告诉 Windows 的 IDEA，你要找我请认准 node1（因为你的 Windows 已经认识 node1 了）
advertised.listeners=PLAINTEXT://node1:9092
```
### 1. 基础设施启动 (虚拟机端)
按顺序启动以下组件：
```bash
#进入kafka目录，启动zookeeper和kafka服务
cd /export/server/kafka_2.12-2.4.1
bin/zookeeper-server-start.sh -daemon config/zookeeper.properties
bin/kafka-server-start.sh -daemon config/server.properties
#打开所有hadoop服务
start-all.sh
#启动hive服务
cd /export/server/hive
nohup bin/hive --service metastore > /tmp/hive-metastore.log 2>&1 &
nohup bin/hiveserver2 > /tmp/hiveserver2.log 2>&1 &
#运行beeline连接hive
beeline -u jdbc:hive2://localhost:10000 -n root

```

### 2. Flink 引擎点火 (Windows IDEA 端)
- 运行 `ZephyrWatchMaster`：开启流计算任务。
- 运行 `SensorDataProducer`：开始推送模拟数据。
- **验证**：访问 http://192.168.88.161:9870 确认 `/zephyr/features/` 下有文件生成。

### 3. Hive 映射表创建 (虚拟机端)
-打开虚拟机的beeline，执行以下 SQL 创建外部表：
-[hive_init.sql](src/main/resources/sql/hive_init.sql)

## 🐍 五、 🛠 已完成的核心功能
目前项目已经搭建起从数据仿真到实时推理的完整闭环：

高并发实时流处理：基于 Flink 实现传感器数据的实时清洗、校验（SensorValidationFilter）和特征窗口聚合（FeatureWindowProcessFunction）。

实时特征工程：利用 Flink 滑动窗口计算传感器的统计特征（均值、标准差等），为模型提供高质量输入。

在线模型推理：集成 JPMML，在 Flink 流中实时加载 .pmml 模型文件，实现低延迟的 RUL 预测。

多维数据持久化：

实时侧：预测结果通过 RulRedisMapper 写入 Redis，支持前端秒级查询。

离线侧：原始特征与预测数据通过 HdfsCsvSinkFactory 存入 HDFS，为后续离线分析和模型微调打下基础。

ML 训练管道：包含完整的 Python 机器学习流程，支持从 Hive/CSV 读取数据、特征构建、随机森林（Random Forest）模型训练及评估。

## 六、系统双分支技术路线

本项目采用“双分支架构”：

```text
分支 A：离线训练链路
Flink 特征工程 → HDFS → Hive 映射 → Python 读取 Hive 训练模型 → MinIO 存储模型

分支 B：在线推理链路
Kafka → Flink 实时特征提取 → 加载 Python 模型 → 实时输出预测结果 → MySQL/Redis/Kafka → Grafana 可视化
```

## 分支 A：离线训练链路

分支 A 负责离线特征加工、训练数据构建、机器学习模型训练、模型评估、模型导出与模型版本管理。

整体链路如下：

```text
Flink 离线特征工程
    ↓
HDFS 特征数据落地
    ↓
Hive 外部表映射
    ↓
Python 读取 Hive 训练模型
    ↓
模型评估、阈值优化、模型融合
    ↓
模型产物上传 MinIO
```

---

## A1. Flink 离线特征工程

### 目标

将历史设备传感器数据转换为可训练的窗口特征数据。

### 使用技术

| 技术 | 作用 |
|---|---|
| Apache Flink 1.17+ | 离线批处理或历史流回放 |
| Kafka / 本地数据源 | 读取历史传感器数据 |
| Sliding Event Time Window | 滑动窗口统计 |
| ProcessFunction | 自定义特征提取 |
| Checkpoint | 保证任务容错 |
| Exactly-Once | 保证数据处理一致性 |

### 实现内容

Flink 读取历史传感器数据后，按照设备编号 `machineId` 分组，并使用滑动窗口提取特征。

#### 窗口设计

```text
窗口大小：30 秒
滑动步长：5 秒
含义：每 5 秒输出一次过去 30 秒的设备状态特征
```

#### 特征类型

| 特征类型 | 示例 |
|---|---|
| 基础统计特征 | mean、max、min、std |
| 趋势特征 | temperatureTrend、speedTrend、pressureTrend |
| 序列特征 | slope、diffMean、lastNAvg |
| 波动特征 | std、range、coefficient of variation |
| 健康状态特征 | RUL、riskLabel、riskProbability |

---

## A2. HDFS 特征数据落地

### 目标

将 Flink 计算出的特征数据写入 HDFS，形成离线训练数据湖。

### 使用技术

| 技术 | 作用 |
|---|---|
| Hadoop HDFS | 存储大规模特征文件 |
| Flink FileSink | 将流式特征写入 HDFS |
| CSV / Parquet | 特征文件格式 |
| DWD / DWS 分层 | 区分明细层和特征汇总层 |

### 存储路径设计

```text
/zephyr/dwd/sensor_clean          清洗后的传感器明细数据
/zephyr/dws/device_feature        窗口级设备特征数据
/zephyr/dws/risk_prediction       离线预测结果数据
```

### 数据分层

| 层级 | 内容 | 用途 |
|---|---|---|
| DWD | 清洗后的原始传感器数据 | 保留明细，支持追溯 |
| DWS | 窗口级统计特征 | 支持模型训练 |
| ADS | 预测结果、告警结果、推荐结果 | 支持展示和分析 |

---

## A3. Hive 外部表映射

### 目标

使用 Hive 将 HDFS 上的特征文件映射成结构化表，使 Python 可以通过 SQL 读取训练数据。

### 使用技术

| 技术 | 作用 |
|---|---|
| Apache Hive | 建立外部表 |
| Hive SQL | 查询特征数据 |
| PyHive | Python 连接 Hive |
| External Table | 不复制数据，只映射 HDFS 文件 |

### Hive 表设计

主要表包括：

| 表名 | 作用 |
|---|---|
| `dwd_sensor_clean` | 清洗后的传感器明细表 |
| `dws_device_feature` | 窗口级特征表 |
| `ads_risk_prediction` | 风险预测结果表 |
| `ads_alert_event` | 告警事件表 |
| `ads_maintenance_recommendation` | 维修推荐结果表 |

---

## A4. Python 读取 Hive 训练模型

### 目标

Python 从 Hive 中读取窗口特征，训练故障风险识别模型、RUL 预测模型和推荐模型。

### 使用技术

| 技术 | 作用 |
|---|---|
| PyHive | 连接 Hive |
| Pandas | 数据处理 |
| Scikit-learn | 基线模型、评估、阈值搜索 |
| XGBoost | 强基线分类模型 |
| LightGBM | 高效梯度提升模型 |
| Optuna / GridSearchCV | 超参数优化 |
| SHAP | 模型解释 |
| sklearn2pmml / ONNX | 模型导出 |
| MinIO | 保存模型文件和元数据 |

### 标签构造

本项目同时支持两类任务。

#### 1. RUL 回归任务

```text
RUL = max_cycle - current_cycle
```

用于预测设备剩余寿命。

#### 2. 故障风险分类任务

```text
riskLabel = 1 if RUL <= 30 else 0
```

含义如下：

| 标签 | 含义 |
|---|---|
| 0 | 正常设备状态 |
| 1 | 高风险设备状态 |

由于高风险样本远少于正常样本，因此本项目不以 Accuracy 作为主要指标，而使用 Precision、Recall、F1-score 和 PR-AUC 评价模型效果。

---

## A5. 机器学习模型设计

### 1. 至少构建 4 种基线模型

| 模型 | 作用 |
|---|---|
| Logistic Regression | 线性基线模型 |
| Random Forest | 非线性集成模型 |
| XGBoost | 表格数据强基线模型 |
| LightGBM | 高效梯度提升模型 |

老师写的加分扩展：

| 模型 | 作用 |
|---|---|
| CatBoost | 类别特征和表格数据建模 |
| MLP | 神经网络基线 |
| Isolation Forest | 无监督异常检测 |
| One-Class SVM | 无监督异常检测 |

### 2. 模型评价指标

本项目不以 Accuracy 作为主要评价指标。

主要指标：

| 指标 | 含义 |
|---|---|
| Precision | 告警中真正故障的比例，衡量误报情况 |
| Recall | 真实高风险样本被识别出来的比例，衡量漏报情况 |
| F1-score | Precision 和 Recall 的综合指标 |
| PR-AUC | 不平衡分类场景下的主要评价指标 |

辅助指标：

| 指标 | 说明 |
|---|---|
| ROC-AUC | 可作为补充，但不作为核心指标 |
| Confusion Matrix | 重点观察 FN 和 FP |
| PR Curve | 对比不同模型在不平衡数据下的表现 |

### 3. 模型优化

本项目支持两类优化方式。

#### 超参数调优

使用 `GridSearchCV` 或 `Optuna` 对模型关键参数进行优化。

重点优化参数：

```text
XGBoost:
- max_depth
- learning_rate
- n_estimators
- subsample
- colsample_bytree
- scale_pos_weight

LightGBM:
- num_leaves
- learning_rate
- n_estimators
- class_weight
```

#### 阈值调整

默认 0.5 阈值不适合不平衡分类任务。

本项目通过 `precision_recall_curve` 搜索最优阈值：

```text
目标：在保证 Recall 的前提下，提高 Precision 和 F1-score
输出：threshold.json
```

### 4. 模型融合

支持两类简单融合方式：

| 方法 | 说明 |
|---|---|
| Voting Classifier | 多模型软投票 |
| Weighted Average | 按模型 PR-AUC 加权平均预测概率 |

融合目标：

```text
提高模型稳定性，降低单一模型误判风险。
```

### 5. 结果分析与可视化图表

机器学习训练完成后，必须输出以下图表：

| 图表 | 文件名 | 作用 |
|---|---|---|
| 类别分布饼图 | `class_distribution_pie.png` | 展示正常样本与高风险样本不平衡 |
| 各模型 PR 曲线对比图 | `pr_curve_compare.png` | 对比不同模型效果 |
| 混淆矩阵热力图 | `confusion_matrix.png` | 重点分析 FN 和 FP |
| XGBoost 特征重要性图 | `xgb_feature_importance.png` | 解释关键传感器特征 |
| Precision-Recall 阈值权衡曲线 | `threshold_pr_curve.png` | 说明阈值选择依据 |
| SHAP 解释图 | `shap_summary.png` | 解释模型为何判定高风险 |

### 6. 模型产物

训练完成后输出以下文件：

```text
zephyr_ml/models/
├── best_model.pmml
├── best_model.pkl
├── threshold.json
├── feature_columns.json
├── model_metadata.json
└── metrics_report.csv
```

模型文件最终上传到 MinIO：

```text
minio://zephyr-models/risk/best_model.pmml
minio://zephyr-models/risk/threshold.json
minio://zephyr-models/risk/feature_columns.json
minio://zephyr-models/risk/model_metadata.json
```

---

## 分支 B：在线推理链路

分支 B 负责实时数据接入、实时特征提取、模型在线推理、CEP 规则检测、异常分流、告警输出、推荐触发和可视化展示。

整体链路如下：

```text
Kafka 实时数据接入
    ↓
Flink 实时特征提取
    ↓
Flink 加载模型进行实时推理
    ↓
CEP 复杂事件处理
    ↓
正常流与异常流分流
    ↓
MySQL / Redis / Kafka / Webhook 输出
    ↓
Grafana 可视化展示
```

---

## B1. Kafka 实时数据接入

### 目标

接入实时设备传感器数据流。

### 使用技术

| 技术 | 作用 |
|---|---|
| Kafka | 接入传感器实时数据 |
| Flink Kafka Source | Flink 消费 Kafka 数据 |
| JSON | 传感器数据传输格式 |
| Watermark | 处理事件时间乱序 |

### Topic 设计

| Topic | 作用 |
|---|---|
| `sensor_raw_topic` | 原始传感器数据 |
| `risk_prediction_topic` | 风险预测结果 |
| `alert_event_topic` | 高风险告警事件 |
| `review_label_topic` | 人工审核标签回流 |
| `recommendation_topic` | 推荐结果事件 |

---

## B2. Flink 实时特征提取

### 目标

使用与离线训练一致的特征工程逻辑，实时生成模型输入特征。

### 使用技术

| 技术 | 作用 |
|---|---|
| Apache Flink 1.17+ | 实时流处理 |
| Keyed Stream | 按设备编号分组 |
| Sliding Event Time Window | 滑动窗口计算 |
| State Backend | 保存窗口状态 |
| Checkpoint | 故障恢复 |
| Exactly-Once | 保证流处理一致性 |
| CEP | 检测连续异常模式 |

### 核心设计

离线训练和在线推理必须共用同一套特征工程逻辑：

```text
离线怎么提特征，在线就怎么提特征。
```

这样可以避免训练和推理阶段特征口径不一致的问题。

---

## B3. Flink 加载模型进行实时推理

### 目标

将 Python 离线训练好的模型部署到 Flink 实时任务中，实现低延迟风险评分。

### 支持部署方式

| 方式 | 技术 | 使用场景 |
|---|---|---|
| PMML 本地推理 | sklearn2pmml + JPMML | 推荐优先实现 |
| ONNX 本地推理 | ONNX Runtime Java | 后续扩展 |
| REST 模型服务 | Flink Async I/O + Flask API | 调试和扩展 |
| TF Serving | TensorFlow Serving | 深度学习模型服务 |

### 在线推理输出

```json
{
  "machineId": 12,
  "windowStart": 1776595125000,
  "windowEnd": 1776595155000,
  "rul": 18.6,
  "riskProbability": 0.86,
  "riskLabel": 1,
  "riskLevel": "HIGH",
  "modelName": "xgboost_v1",
  "threshold": 0.18
}
```

---

## B4. CEP 复杂事件处理

### 目标

在机器学习模型之外，增加规则兜底能力，识别连续异常模式。

### 使用技术

| 技术 | 作用 |
|---|---|
| Flink CEP | 复杂事件模式识别 |
| Pattern API | 定义连续异常模式 |
| Side Output | 将异常事件分流 |
| Kafka Alert Topic | 写入告警流 |

### CEP 规则示例

| 规则 | 条件 | 告警等级 |
|---|---|---|
| 转速连续波动 | 连续 3 个窗口 `speedStd` 超过阈值 | HIGH |
| 温度持续升高 | 连续 3 个窗口 `temperatureTrend > 0` | MEDIUM |
| 压力剧烈波动 | `pressureStd` 突然升高 | MEDIUM |
| RUL 快速下降 | 连续多个窗口 RUL 降低 | HIGH |
| 模型高风险 + CEP 命中 | 两类风险同时出现 | CRITICAL |

---

## B5. 正常流与异常流分流

### 目标

根据模型预测结果和 CEP 规则，将设备状态分成正常行为和异常行为。

### 使用技术

| 技术 | 作用 |
|---|---|
| Flink Side Output | 正常流和异常流分离 |
| MySQL Sink | 保存预测结果和告警事件 |
| Redis Sink | 缓存实时状态 |
| Kafka Sink | 写入异常事件流 |
| Webhook Sink | 推送企业微信/钉钉 |

### 分流逻辑

```text
正常行为：
riskProbability < threshold 且 CEP 未命中
    → 写入 MySQL
    → 写入 Redis
    → 供 Grafana 和后端查询

异常行为：
riskProbability >= threshold 或 CEP 命中
    → 写入 Kafka alert_event_topic
    → 写入 MySQL alert_event
    → 推送企业微信/钉钉
    → 进入人工审核流程
```

---

## B6. 输出与告警

### MySQL 结果表

#### 1. 预测结果表

```sql
CREATE TABLE device_risk_prediction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    machine_id INT NOT NULL,
    window_start BIGINT NOT NULL,
    window_end BIGINT NOT NULL,
    rul DOUBLE,
    risk_probability DOUBLE,
    risk_label INT,
    risk_level VARCHAR(20),
    model_name VARCHAR(100),
    threshold_value DOUBLE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 2. 告警事件表

```sql
CREATE TABLE alert_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    machine_id INT NOT NULL,
    alert_type VARCHAR(50),
    risk_probability DOUBLE,
    risk_level VARCHAR(20),
    alert_reason VARCHAR(500),
    source VARCHAR(50),
    status VARCHAR(20) DEFAULT 'PENDING',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 3. 人工审核表

```sql
CREATE TABLE alert_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_id BIGINT NOT NULL,
    machine_id INT NOT NULL,
    review_label INT NOT NULL,
    reviewer VARCHAR(100),
    review_comment VARCHAR(500),
    review_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 4. 推荐结果表

```sql
CREATE TABLE maintenance_recommendation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    machine_id INT NOT NULL,
    alert_id BIGINT,
    recommend_type VARCHAR(50),
    recommend_name VARCHAR(200),
    recommend_reason VARCHAR(500),
    score DOUBLE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## B7. 在线学习闭环

### 目标

将人工审核后的真实标签回流到训练链路，支持模型周期性更新。

### 使用技术

| 技术 | 作用 |
|---|---|
| MySQL `alert_review` | 保存人工审核标签 |
| Kafka `review_label_topic` | 审核标签流 |
| Flink Batch / Python 定时任务 | 触发增量训练 |
| sklearn.utils.shuffle | 模拟流式样本回放 |
| MinIO | 保存新版本模型 |
| PMML / ONNX | 部署新模型 |

### 流程

```text
异常告警
    ↓
人工审核：真实故障 / 误报
    ↓
审核标签写入 MySQL
    ↓
标签同步到训练数据集
    ↓
Python 周期性重新训练模型
    ↓
导出新版 PMML / ONNX
    ↓
上传 MinIO
    ↓
Flink 重新加载新模型
```

---

## B8. Flask 风险评分 API

### 目标

提供一个轻量级模型服务接口，便于调试、前端调用和 REST 推理扩展。

### 使用技术

| 技术 | 作用 |
|---|---|
| Flask | 构建风险评分接口 |
| Scikit-learn / XGBoost | 加载训练模型 |
| threshold.json | 使用最优阈值 |
| REST API | 提供模型推理服务 |

### 接口设计

```text
POST /api/risk/score
```

输入示例：

```json
{
  "pressureAvg": 10.2,
  "pressureStd": 0.3,
  "temperatureAvg": 643.1,
  "temperatureTrend": 2.1,
  "speedAvg": 1500.3,
  "speedStd": 8.2
}
```

输出示例：

```json
{
  "riskProbability": 0.86,
  "riskLabel": 1,
  "riskLevel": "HIGH",
  "threshold": 0.18
}
```

---

## 七、推荐系统设计

## 1. 推荐系统定位

本项目的推荐系统不是商品推荐，而是面向工业维护场景的智能维护推荐系统。

当设备被判定为高风险后，系统自动推荐：

| 推荐内容 | 示例 |
|---|---|
| 维修策略 | 降载运行、停机检查、传感器复核 |
| 备件推荐 | 转速传感器、压力传感器、轴承组件 |
| 相似案例 | 历史相似故障设备及处理记录 |
| 工单优先级 | 高危设备优先处理 |
| 维修人员 | 根据经验和空闲状态推荐处理人员 |

---

## 2. 推荐系统与 Flink 的结合

推荐系统与在线推理链路结合，不单独孤立运行。

### 实时触发流程

```text
Flink 实时风险预测
    ↓
发现高风险设备
    ↓
抽取当前设备特征
    ↓
调用推荐逻辑
    ↓
生成维修策略 / 备件 / 相似案例推荐
    ↓
写入 MySQL 和 Redis
    ↓
Grafana 展示推荐结果
```

Flink 在推荐系统中的作用：

| 作用 | 说明 |
|---|---|
| 实时触发 | 高风险事件触发推荐 |
| 特征构造 | 生成设备当前状态向量 |
| 事件分发 | 将推荐请求写入 Kafka |
| 结果输出 | 将推荐结果写入 MySQL / Redis |
| 可视化支撑 | 为 Grafana 提供实时推荐数据 |

---

## 八. 推荐算法路线

### 第一阶段：相似案例推荐

优先实现，落地难度低。

| 技术 | 作用 |
|---|---|
| KNN | 查找相似设备状态 |
| Cosine Similarity | 计算当前设备与历史案例相似度 |
| MySQL | 存储历史案例 |
| Redis | 缓存推荐结果 |

输入：

```text
当前设备风险特征向量
```

输出：

```text
TopN 相似故障案例
推荐维修动作
推荐备件
推荐工单优先级
```

### 第二阶段：Two-Tower 双塔推荐模型

作为主流推荐算法扩展。

| 推荐系统概念 | 本项目对应 |
|---|---|
| User Tower | 当前设备状态、风险特征、历史维修记录 |
| Item Tower | 维修策略、备件、历史故障案例 |
| 向量相似度 | 设备状态与维修方案匹配度 |
| 输出 | TopN 维修策略 / 备件 / 案例 |

使用技术：

```text
PyTorch / TensorFlow + MySQL + Redis + Flink
```

### 第三阶段：LightGCN 图推荐

用于建模设备、故障、维修动作和备件之间的图关系。

图结构：

```text
设备 —— 故障类型 —— 维修动作 —— 备件 —— 工单
```

推荐目标：

```text
根据当前设备风险状态，推荐最可能有效的维修动作和备件。
```

使用技术：

```text
PyTorch Geometric / DGL + LightGCN
```

### 第四阶段：SASRec / BERT4Rec 序列推荐

用于根据设备历史状态序列预测下一步维护动作。

输入：

```text
设备历史状态序列
```

输出：

```text
下一步推荐维修动作
```

该阶段作为挑战高分扩展，优先级低于相似案例推荐和 Two-Tower。

---

## 九、Prometheus + Grafana 可视化设计

## 1. 可视化目标

Grafana 不仅展示业务结果，还展示系统运行状态。

展示内容分为两类：

| 类型 | 内容 |
|---|---|
| 业务监控 | 风险概率、RUL、告警数量、推荐结果 |
| 系统监控 | Flink 吞吐量、延迟、Checkpoint、Kafka 堆积 |

---

## 2. 数据源设计

| 数据源 | 用途 |
|---|---|
| MySQL | 预测结果、告警事件、推荐结果 |
| Redis | 实时设备状态缓存 |
| Prometheus | Flink、Kafka、JVM、系统指标 |
| MinIO | 模型版本和模型元数据 |

---

## 3. Grafana 面板设计

| 面板 | 数据来源 | 说明 |
|---|---|---|
| 实时风险概率趋势 | MySQL | 展示各设备风险概率变化 |
| RUL 剩余寿命趋势 | MySQL / Redis | 展示设备剩余寿命变化 |
| 高风险告警列表 | MySQL | 展示最近异常设备 |
| 每小时告警数量 | MySQL | 统计告警趋势 |
| 推荐维修策略 TopN | MySQL | 展示维修建议 |
| Flink 吞吐量 | Prometheus | 展示实时处理能力 |
| Flink Checkpoint 状态 | Prometheus | 展示容错状态 |
| Kafka 消息堆积 | Prometheus | 展示消息队列压力 |
| 模型版本信息 | MySQL / MinIO | 展示当前线上模型版本 |

---

## 4. Grafana SQL 示例

### 实时风险趋势

```sql
SELECT
  create_time AS time,
  risk_probability,
  machine_id AS metric
FROM device_risk_prediction
WHERE $__timeFilter(create_time);
```

### 高风险告警列表

```sql
SELECT
  create_time AS time,
  machine_id,
  risk_probability,
  risk_level,
  alert_reason
FROM alert_event
WHERE risk_level IN ('HIGH', 'CRITICAL')
ORDER BY create_time DESC
LIMIT 50;
```

### 每小时告警数量

```sql
SELECT
  $__timeGroup(create_time, '1h') AS time,
  COUNT(*) AS alert_count
FROM alert_event
WHERE $__timeFilter(create_time)
GROUP BY time
ORDER BY time;
```

### 推荐维修策略

```sql
SELECT
  create_time AS time,
  machine_id,
  recommend_type,
  recommend_name,
  score,
  recommend_reason
FROM maintenance_recommendation
ORDER BY create_time DESC
LIMIT 20;
```

---

## 十、总体项目目录结构

```text
zephyr-watch
├── src/main/java/com/zephyr/watch
│   ├── app
│   │   ├── OfflineFeatureJob.java              # 离线特征工程任务
│   │   ├── OnlineInferenceJob.java             # 在线推理主任务
│   │   ├── AlertReviewJob.java                 # 审核标签回流任务
│   │   └── RecommendJob.java                   # 推荐结果生成任务
│   │
│   ├── config
│   │   ├── KafkaConfig.java
│   │   ├── FlinkConfig.java
│   │   ├── StorageConfig.java
│   │   ├── ModelConfig.java
│   │   └── AlertConfig.java
│   │
│   ├── model
│   │   ├── SensorReading.java
│   │   ├── FeatureVector.java
│   │   ├── RiskPrediction.java
│   │   ├── AlertEvent.java
│   │   ├── ReviewLabel.java
│   │   └── MaintenanceRecommendation.java
│   │
│   ├── process
│   │   ├── SensorValidationFilter.java
│   │   ├── FeatureWindowProcessFunction.java
│   │   ├── SequenceFeatureProcessFunction.java
│   │   ├── RiskPredictFunction.java
│   │   ├── CepRiskPatternFunction.java
│   │   └── RiskRouteProcessFunction.java
│   │
│   ├── sink
│   │   ├── HdfsCsvSinkFactory.java
│   │   ├── MysqlSinkFactory.java
│   │   ├── RedisRiskMapper.java
│   │   ├── KafkaAlertSinkFactory.java
│   │   └── WebhookAlertSink.java
│   │
│   ├── source
│   │   ├── KafkaSensorSourceFactory.java
│   │   └── ReviewLabelSourceFactory.java
│   │
│   └── recommend
│       ├── SimilarCaseRecall.java
│       ├── MaintenanceRuleRanker.java
│       └── RecommendationService.java
│
├── zephyr_ml
│   ├── data
│   │   ├── build_dataset_from_hive.py
│   │   ├── make_stream_dataset.py
│   │   └── feature_schema.py
│   │
│   ├── train
│   │   ├── train_baselines.py
│   │   ├── tune_xgboost_optuna.py
│   │   ├── threshold_search.py
│   │   ├── ensemble_train.py
│   │   ├── unsupervised_anomaly.py
│   │   ├── incremental_retrain.py
│   │   └── export_model.py
│   │
│   ├── explain
│   │   └── shap_explain.py
│   │
│   ├── recommend
│   │   ├── case_similarity.py
│   │   ├── two_tower_train.py
│   │   ├── lightgcn_train.py
│   │   └── sequence_recommend.py
│   │
│   ├── api
│   │   └── risk_api.py
│   │
│   ├── models
│   │   ├── best_model.pmml
│   │   ├── best_model.onnx
│   │   ├── best_model.pkl
│   │   ├── threshold.json
│   │   ├── feature_columns.json
│   │   └── model_metadata.json
│   │
│   └── reports
│       ├── class_distribution_pie.png
│       ├── pr_curve_compare.png
│       ├── confusion_matrix.png
│       ├── xgb_feature_importance.png
│       ├── threshold_pr_curve.png
│       └── shap_summary.png
│
├── src/main/resources
│   ├── sql
│   │   ├── hive_init.sql
│   │   ├── mysql_init.sql
│   │   └── grafana_queries.sql
│   │
│   └── models
│       └── best_model.pmml
│
├── grafana
│   ├── provisioning
│   │   ├── datasources
│   │   │   ├── mysql.yml
│   │   │   └── prometheus.yml
│   │   └── dashboards
│   │       └── dashboards.yml
│   │
│   └── dashboards
│       └── zephyr-watch-dashboard.json
│
├── prometheus
│   └── prometheus.yml
│
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## 十一、完整运行流程

## 1. 启动基础组件

需要启动：

```text
Kafka
Hadoop HDFS
Hive Metastore
HiveServer2
MySQL
Redis
MinIO
Prometheus
Grafana
```

---

## 2. 创建 Hive 外部表

在 Hive 中执行：

```text
src/main/resources/sql/hive_init.sql
```

用于创建：

```text
dwd_sensor_clean
dws_device_feature
```

---

## 3. 创建 MySQL 业务表

在 MySQL 中执行：

```text
src/main/resources/sql/mysql_init.sql
```

用于创建：

```text
device_risk_prediction
alert_event
alert_review
maintenance_recommendation
model_registry
```

---

## 4. 运行 Flink 离线特征任务

运行：

```text
OfflineFeatureJob
```

任务作用：

```text
读取历史传感器数据
    ↓
清洗与校验
    ↓
滑动窗口特征工程
    ↓
写入 HDFS
    ↓
Hive 映射为训练表
```

验证方式：

```text
HDFS 中出现 /zephyr/dws/device_feature 数据文件
Hive 中可以 SELECT 查询特征数据
```

---

## 5. 运行 Python 机器学习训练

进入：

```text
zephyr_ml
```

执行：

```bash
python data/build_dataset_from_hive.py
python train/train_baselines.py
python train/tune_xgboost_optuna.py
python train/threshold_search.py
python train/ensemble_train.py
python train/unsupervised_anomaly.py
python explain/shap_explain.py
python train/export_model.py
```

训练完成后生成：

```text
best_model.pmml
threshold.json
metrics_report.csv
模型评估图表
```

---

## 6. 上传模型到 MinIO

上传内容：

```text
best_model.pmml
best_model.onnx
best_model.pkl
threshold.json
feature_columns.json
model_metadata.json
```

MinIO 用于统一管理模型版本，方便 Flink 在线任务加载最新模型。

---

## 7. 运行 Flink 在线推理任务

运行：

```text
OnlineInferenceJob
```

任务流程：

```text
Kafka 实时传感器数据
    ↓
Flink 清洗与滑动窗口特征提取
    ↓
加载 PMML / ONNX 模型
    ↓
输出 riskProbability 和 RUL
    ↓
正常流写 MySQL / Redis
    ↓
异常流写 Kafka / MySQL / 企业微信 / 钉钉
    ↓
触发推荐系统
```

---

## 8. 运行推荐系统任务

运行：

```text
RecommendJob
```

任务流程：

```text
高风险告警事件
    ↓
提取设备当前状态特征
    ↓
匹配历史相似故障案例
    ↓
生成维修策略、备件和工单优先级推荐
    ↓
写入 MySQL / Redis
    ↓
Grafana 展示
```

---

## 9. 启动 Grafana 展示

Grafana 展示内容包括：

```text
设备风险概率趋势
RUL 剩余寿命曲线
高风险告警列表
告警数量统计
推荐维修策略 TopN
Flink 吞吐量
Flink Checkpoint 状态
Kafka 消息堆积
模型版本信息
```

---

## 十二、当前已完成与待补充功能

## 1. 已完成基础能力

当前项目已经具备以下基础：

| 功能 | 状态 |
|---|---|
| Kafka 数据接入 | 已有基础 |
| Flink 流式处理 | 已有基础 |
| 传感器数据清洗 | 已有基础 |
| 窗口特征工程 | 已有基础 |
| HDFS 特征存储 | 已有基础 |
| Hive 外部表映射 | 已有基础 |
| Python 读取 Hive | 已有基础 |
| Random Forest RUL 训练 | 已有基础 |
| PMML 在线推理 | 已有基础 |
| Redis 写入预测结果 | 已有基础 |

---

## 2. 必须补充能力

后续必须补齐：

| 模块 | 待实现内容 |
|---|---|
| Flink | 升级 1.17+、滑动窗口、状态后端、Exactly-Once、CEP、Side Output |
| 机器学习 | 4 种基线模型、PR-AUC、阈值优化、模型融合、SHAP、无监督异常检测 |
| 模型部署 | PMML/ONNX、MinIO 模型仓库、模型版本管理 |
| 输出告警 | MySQL Sink、Kafka Alert Topic、企业微信/钉钉 Webhook |
| 在线学习 | 人工审核标签回流、周期性再训练 |
| 推荐系统 | 相似案例推荐、Two-Tower、LightGCN、维修策略推荐 |
| 可视化 | Prometheus + Grafana 业务监控和系统监控 |
| API | Flask 风险评分接口 |

---

## 十三、项目创新点

## 1. Flink 实时特征工程与在线推理融合

系统不只是离线训练模型，而是在 Flink 流上完成实时特征工程和实时模型推理，实现从数据进入到风险输出的低延迟闭环。

## 2. 离线与在线特征同源

离线训练和在线推理共用同一套特征工程逻辑，避免训练数据和线上推理数据特征口径不一致的问题。

## 3. 模型预测 + CEP 规则双通道预警

系统同时使用机器学习模型和 CEP 规则：

```text
机器学习模型识别复杂非线性风险；
CEP 规则识别连续异常模式；
二者结合提升告警可靠性。
```

## 4. 面向不平衡分类的模型评估体系

由于高风险样本远少于正常样本，本项目不以 Accuracy 为核心指标，而使用 Precision、Recall、F1-score 和 PR-AUC 评价模型，更符合工业预警场景。

## 5. 阈值自适应优化

系统通过 `precision_recall_curve` 搜索最佳阈值，不使用默认 0.5 阈值，从而降低漏报风险。

## 6. 人工审核标签回流与模型更新

异常告警经过人工审核后，标签可以回流到训练 pipeline，支持周期性再训练和模型更新，形成持续优化闭环。

## 7. MinIO 模型仓库

使用 MinIO 管理 PMML、ONNX、PKL、阈值文件和模型元数据，支持模型版本化管理和在线任务加载。

## 8. 智能维护推荐系统

系统不仅判断设备是否高风险，还能根据当前风险状态推荐维修策略、备件和历史相似案例，提高系统业务价值。

## 9. Prometheus + Grafana 双层监控

Grafana 同时展示业务指标和系统指标：

```text
业务指标：风险概率、RUL、告警数量、推荐结果；
系统指标：Flink 吞吐量、延迟、Checkpoint、Kafka 堆积。
```

---

## 十四、最终项目定位

Zephyr-Watch 最终定位为：

> 基于 Flink、机器学习和推荐系统的工业设备实时预警与智能维护推荐平台。

项目技术路线为：

```text
数据采集层：
Kafka / Pulsar

实时计算层：
Apache Flink 1.17+
滑动窗口
状态管理
Exactly-Once
CEP
Side Output

离线数仓层：
HDFS
Hive

机器学习层：
Scikit-learn
XGBoost
LightGBM
PyTorch / TensorFlow
SHAP
PMML / ONNX

模型管理层：
MinIO

在线服务层：
Flink UDF
Flask API
TF Serving

结果存储层：
MySQL
Redis

告警层：
Kafka Alert Topic
企业微信 / 钉钉 Webhook

推荐系统层：
KNN
Two-Tower
LightGCN
SASRec / BERT4Rec

监控展示层：
Prometheus
Grafana
```

最终系统形成：

```text
实时感知
    ↓
Flink 特征工程
    ↓
机器学习风险识别
    ↓
异常告警
    ↓
人工审核
    ↓
模型更新
    ↓
维修推荐
    ↓
Grafana 可视化
```







