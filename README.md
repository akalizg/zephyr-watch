# Zephyr-Watch: 工业设备实时预警与预测性维护平台

Zephyr-Watch 是一套集成大数据流计算与机器学习的工业物联网（IIoT）解决方案。本项目基于 NASA C-MAPSS 发动机数据集，实现了从底层传感器采集、实时特征提炼、分布式存储到 AI 预测的完整闭环。

## 一、 项目背景与业务逻辑

### 1. 为什么做这个项目？
在重型工业领域，传统的"定期维护"会导致过度维修成本，而"事后维修"则面临停机造成的巨大经济损失。Zephyr-Watch 通过"预测性维护"技术，实时监测传感器数据波动，在故障发生前精准计算设备的 剩余使用寿命 (RUL, Remaining Useful Life)，实现"零宕机"愿景。

### 2. 数据集解析：NASA C-MAPSS (FD001)
- **物理含义**：模拟涡扇发动机在不同磨损程度下的运行快照。
- **核心数据**：26 列数据，包含机器 ID、运行周期（Cycle）、3 组运行设置和 21 个传感器值（温度、压力、转速等）。
- **技术挑战**：传感器伴随严重的高频随机噪声。单点数据无法反映健康状况，必须通过 Flink 的滑动窗口捕捉时间序列趋势特征。

## 🏗️ 二、 技术栈与架构走向

### 1. 技术栈
- **计算层**：Apache Flink 1.15.2 (流处理核心)
- **传输层**：Apache Kafka 3.0.0 (高并发消息队列)
- **存储层**：Hadoop HDFS 3.3.0 (分布式数据湖)
- **管理层**：Apache Hive 3.1.2 (特征仓库映射)
- **算法层**：Python 3.x + Scikit-learn (机器学习)
- **展示层 (规划中)**：Spring Boot + Echarts

### 2. 系统双轨走向
- **分支 A (离线训练)**：Flink 特征工程 → HDFS → Hive 映射 → Python 读取 Hive 训练模型。
- **分支 B (在线推理)**：Flink 实时特征提取 → 加载 Python 模型 → 实时输出预测结果 → 可视化。

## 三、 核心运用：HDFS 与 Hive 的分工

本项目不使用本地文件存储，而是采用正统的大数据存储方案：

### HDFS (分布式文件系统)
- 作为"数据湖"，负责存储 Flink 实时算出的特征文件。
- 存储路径：`/zephyr/features/`
- 特点：能够承载海量、高速写入的特征数据。

### Hive (数据仓库)
- 作为"管理账本"，通过外部表 (External Table) 映射 HDFS 里的文件。
- 运用逻辑：由于 HDFS 的文件是碎片化的文本，Hive 通过定义表结构（Schema），让 Python 能以标准 SQL 语句轻松抓取训练集。

## 📁 四、 项目目录说

```
com.zephyr.watch
├── app             # Flink 实时特征提取任务 (核心引擎)
├── bean            # 数据实体类 (POJO)，统一数据标准
├── simulator       # NASA 数据集模拟发送器 (传感器模拟)
└── resources       # 配置文件 (Log4j, 属性配置等)
```

## 🛠️ 五、 完整操作指南
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

## 🐍 六、 下一步：Python 机器学习实战

### 1. 编写位置
为了保持项目整洁，请在项目根目录下创建一个新的目录 `zephyr_ml`。
```
Zephyr-Watch
├── src/main/java (Java代码)
├── zephyr_ml (在这里写Python代码)
│   ├── data_fetch.py (从Hive读取数据)
│   ├── model_train.py (训练模型)
│   └── wind_turbine_model.pkl (产出的模型)
└── pom.xml
```

### 2. 核心算法思想
根据课上学的python机器学习定算法

## 🚀 七、 项目路线图

- [x] Week 1-2: 环境搭建、Kafka 网络打通、Flink 窗口计算、HDFS 持久化落盘。
- [ ] Week 3: Hive 外部表映射，编写 Python 脚本进行模型训练与评估。
- [ ] Week 4: 实现 Flink 在线调用模型推理，将结果存入 MySQL。
- [ ] Week 5: 搭建 Spring Boot 后端与 Echarts 可视化大屏。

