# Zephyr-Watch: 工业设备实时预警平台

Zephyr-Watch 是一款基于 NASA C-MAPSS 涡扇发动机数据集开发的预测性维护系统。它实现了从底层传感器数据采集、实时流式特征计算，到分布式数据湖存储的全链路闭环，旨在通过大数据与 AI 技术预测工业设备的剩余使用寿命 (RUL)。

## 🏗️ 当前系统架构 (已完成)

目前项目已打通"数据大动脉"，实现了高并发、低延迟的流式数据处理链路：

### 数据采集层 (Simulator)
- 使用 Java 编写 SensorDataProducer，模拟工业现场传感器
- 实时读取 train_FD001.txt 数据集，以 JSON 格式推送至 Kafka

### 消息缓冲层 (Kafka)
- 构建单节点 Kafka 集群，作为数据总线
- 实现数据生产与消费的深度解耦

### 实时计算层 (Flink)
- 核心引擎 ZephyrWatchMaster 采用 Tumbling Window (滚动窗口) 机制
- **特征工程**：每 5 秒对原始数据进行聚合，提取"最大压力"、"平均温度"等关键健康指标
- **容错机制**：开启 Checkpoint 存档点，确保流处理的高可靠性

### 数据存储层 (Hadoop HDFS)
- 利用 Flink FileSink 将清洗后的特征数据实时写入 HDFS
- 实现离线数仓存储，为后续机器学习提供"高质量特征集"

## 📁 项目目录说明

```
com.zephyr.watch
├── app             # Flink 实时特征提取任务 (核心引擎)
├── bean            # 数据实体类 (POJO)，统一数据标准
├── simulator       # NASA 数据集模拟发送器 (传感器模拟)
└── resources       # 配置文件 (Log4j, 属性配置等)
```

## 🚀 后续开发计划 (Next Steps)

项目将按照"离线炼丹，在线推理，前端展示"的节奏分阶段推进：

### 第一阶段：AI 模型构建 (Week 3)
- **数据拉取**：编写 Python 脚本从 HDFS 读取特征文件
- **RUL 计算**：根据周期总数反向标注 RUL（剩余寿命）标签
- **炼丹任务**：使用 scikit-learn 训练随机森林回归模型
- **模型序列化**：导出 .pkl 模型文件，作为系统的"智能大脑"

### 第二阶段：实时预测链路 (Week 4)
- **推理升级**：升级 Flink 任务，通过模型接口对实时窗口特征进行 RUL 预测
- **结果落地**：将预测的 RUL 结果存入 MySQL，便于前端低延迟查询
- **阈值报警**：实现 RUL < 30 周期时的实时告警逻辑

### 第三阶段：Spring Boot 可视化 (Week 5)
- **后端开发**：基于 Spring Boot 搭建 Web 服务，提供 RESTful 预警接口
- **前端大屏**：结合 Echarts 绘制动态仪表盘，实现风机健康状况的实时可视化监控

## 🛠️ 环境启动指南

### 虚拟机端
```bash
# 启动 Zookeeper
zkServer.sh start

# 启动 Kafka
bin/kafka-server-start.sh -daemon config/server.properties

# 启动 HDFS
start-dfs.sh
```

### Windows 端
```bash
# 运行 Flink 任务
运行 ZephyrWatchMaster

# 运行数据模拟器
运行 SensorDataProducer
```

### 验货
访问 http://192.168.88.161:9870 查看 HDFS 文件生成情况。

## 💡 项目寄语

从 0 到 1 搭建架构最难，后续的算法和业务只是在这个稳固底座上的舞蹈。