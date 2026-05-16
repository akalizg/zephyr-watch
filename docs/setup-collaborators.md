# 协作成员：从零运行 Zephyr-Watch（含任务 C：Webhook + Grafana/Prometheus）

本文把常见课堂/答辩环境（Windows 本机 + 老师虚拟机上的 Kafka/Hadoop/Hive）与仓库当前实现**对齐**，避免「按飞书步骤拉起 Grafana 却看不到预置看板」「Webhook 表缺列」等问题。路径请按你本机实际目录替换文中的 `D:\work\zephyr-watch`。

---

## 1. 拉取项目

```bat
cd /d D:\work
git clone https://github.com/akalizg/zephyr-watch.git
cd zephyr-watch
```

---

## 2. 创建 MySQL 库表

1. 在 Navicat / MySQL 客户端中**新建数据库** `zephyr_watch`（字符集 `utf8mb4` 等按学校要求即可）。
2. 在库上执行仓库内脚本（二选一或组合）：
   - **新环境**：执行  
     `zephyr-flink-job/src/main/resources/sql/mysql_init.sql`  
     其中已包含 `webhook_config`、`webhook_send_log` 等任务 C 所需表。
   - **旧库已跑过早期 SQL、缺 Webhook 加签/发送日志表**：在备份后追加执行  
     `zephyr-flink-job/src/main/resources/sql/mysql_webhook_task_c_upgrade.sql`  
     （说明见 [`c-observability.md`](c-observability.md) §1.2。）
3. 若曾单独做过反馈表迁移，按需执行：  
   `zephyr-flink-job/src/main/resources/sql/mysql_p0_feedback_migration.sql`  
   （以 README「P0 当前运行说明」为准。）

---

## 3. 虚拟机 Kafka（端口与防火墙）

在虚拟机（如 `node1`）上编辑：

```bash
vi /export/server/kafka_2.12-2.4.1/config/server.properties
```

建议包含（与 README 一致）：

```properties
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://node1:9092
```

重启 Kafka（按你环境选择 stop/start 方式），并保证本机 `hosts` 能解析 `node1` 指向该虚拟机 IP。防火墙若拦截 9092，需放行或按实验要求关闭。

验证进程：

```bash
jps
# 期望看到 Kafka、QuorumPeerMain 等
```

---

## 4. Hive / HDFS（特征与离线训练）

按课程要求启动 ZooKeeper、Kafka、Hadoop、Hive Metastore / HiveServer2 后，用 Beeline 连接，执行仓库中的：

`zephyr-flink-job/src/main/resources/sql/hive_init.sql`

（具体 Beeline URL、用户名以老师给的为准。）

---

## 5. Python 虚拟环境与依赖

- 建议使用 **Python 3.10 或 3.11**（与 `zephyr-ml/requirements.txt` 兼容）。
- 可在**仓库根目录**创建 `.venv`，或在 `zephyr-ml` 下使用 `.venv1`；与一键脚本关系如下：
  - `run-all.bat` 优先使用 `%项目根%\.venv\Scripts\python.exe`，不存在则尝试 `zephyr-ml\.venv1\Scripts\python.exe`，再退回系统 `python`。

示例（根目录 `.venv`）：

```bat
cd /d D:\work\zephyr-watch
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -U pip
.\.venv\Scripts\python.exe -m pip install -r zephyr-ml\requirements.txt
.\.venv\Scripts\python.exe -m pip install -r zephyr-ml\requirements-serve.txt
```

---

## 6. MinIO（模型仓库）

1. 放置 `minio.exe`（或老师发的包），例如 `D:\minio\minio.exe`。
2. 准备数据目录，例如 `D:\minio-data`。
3. PowerShell 示例（账号密码需与 `.env` / `ZEPHYR_MINIO_*` 一致）：

```powershell
cd D:\minio
$env:MINIO_ROOT_USER="admin"
$env:MINIO_ROOT_PASSWORD="admin123456"
.\minio.exe server D:\minio-data --console-address ":9001"
```

控制台默认 <http://localhost:9001>。`.env.example` 中的 `ZEPHYR_MINIO_ACCESS_KEY` / `ZEPHYR_MINIO_SECRET_KEY` 应与上述一致。

---

## 7. Grafana（推荐：Compose + 预置数据源与看板）

**不要**仅用 `docker run ... grafana/grafana-enterprise` 空容器——那样没有本仓库的 **MySQL/Prometheus 数据源** 和 **业务/系统 Dashboard**。

在**已安装 Docker Desktop** 的机器上：

```bat
cd /d D:\work\zephyr-watch\zephyr-dashboard
docker compose -f docker-compose.grafana.yml up -d
```

- 默认 <http://localhost:3000>，初始账号密码见 `docker-compose.grafana.yml`（一般为 `admin` / `admin`）。
- Compose 会读取**仓库根目录**的 `.env`（与可选的 `zephyr-dashboard/.env`），用于 `ZEPHYR_GRAFANA_MYSQL_*` 等，从而连上你本机 MySQL。
- `mysql.yml` 默认使用 `host.docker.internal:3306` 访问**宿主机 MySQL**；若 MySQL 只在虚拟机上，需改 provisioning 里的 `url` 或改用可路由的 IP（见 [`zephyr-dashboard/README.md`](../zephyr-dashboard/README.md)）。

**系统大盘（Prometheus）**：`zephyr-watch-system.json` 依赖 Prometheus 数据源，默认指向 `http://host.docker.internal:9090`。你需要在本机另起 Prometheus 进程（或容器），并加载 `zephyr-dashboard/prometheus/prometheus.yml`，且保证能抓到本机 API 的 `/actuator/prometheus`（详见 [`c-observability.md`](c-observability.md) §2）。**仅看 MySQL 业务大盘**可不启 Prometheus。

---

## 8. Redis（本机）

按课程提供的 `redis-server.exe` 与配置文件启动；验证：

```bat
redis-cli.exe -p 6379 ping
```

期望返回 `PONG`。`ZEPHYR_REDIS_HOST` / `ZEPHYR_REDIS_PORT` 与之一致。

---

## 9. Kafka Topic

在虚拟机 Kafka `bin` 目录下（`bootstrap-server` 换成你的 broker 地址，示例为 `192.168.88.161:9092`）：

```bash
./kafka-topics.sh --create --topic iot_sensor_data --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
./kafka-topics.sh --create --topic risk_prediction_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
./kafka-topics.sh --create --topic alert_event_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
./kafka-topics.sh --create --topic review_label_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
./kafka-topics.sh --create --topic invalid_sensor_topic --bootstrap-server 192.168.88.161:9092 --partitions 1 --replication-factor 1
./kafka-topics.sh --list --bootstrap-server 192.168.88.161:9092
```

`.env` 里 `ZEPHYR_KAFKA_BOOTSTRAP_SERVERS` 必须与这里一致。

---

## 10. 配置 `.env`

仓库根目录只有 `.env.example` 时：

```bat
copy .env.example .env
```

用编辑器打开 `.env`，**至少**核对：

| 变量 | 说明 |
|------|------|
| `ZEPHYR_MYSQL_URL` / `USER` / `PASSWORD` | 与本机或远程 MySQL 一致；Flink 与 API 都读环境变量或等价配置 |
| `ZEPHYR_KAFKA_BOOTSTRAP_SERVERS` | 与虚拟机 Kafka 一致（如 `192.168.88.161:9092`） |
| `ZEPHYR_HDFS_ROOT` | 与虚拟机 HDFS 一致 |
| `ZEPHYR_LOAD_ACTIVE_MODEL` | 需从 MinIO 拉已激活模型时设为 `true` |
| `ZEPHYR_GRAFANA_MYSQL_DATABASE` / `USER` / `PASSWORD` | 与 Grafana 连 MySQL 一致（通常与上面 MySQL 相同库） |
| `ZEPHYR_ALERT_WEBHOOK_*`、`ZEPHYR_WEBHOOK_*` | 可选；Webhook 行为见 [`c-observability.md`](c-observability.md) |

**不要**把含真实密码的 `.env` 提交到 Git。

可选：若本机 JDK 不在 PATH 中，可在 `.env` 增加一行（示例）：

```env
JAVA_HOME=C:\Program Files\Java\jdk1.8.0_xxx
```

`run-all.bat` 启动时会加载 `.env` 中的变量（见下一节）。

---

## 11. 注册模型到 MinIO

先启动 Spring Boot API（或按下一节一键脚本顺序），再执行：

```bat
cd /d D:\work\zephyr-watch\zephyr-ml
D:\work\zephyr-watch\.venv\Scripts\python.exe train\register_model.py --activate
```

（若你用的是 `zephyr-ml\.venv1`，则替换为对应路径。）MinIO 控制台中能看到 bucket/对象即表示上传成功。

---

## 12. 一键启动全链路 `run-all.bat`

在项目根目录：

```bat
run-all.bat zephyr-flink-job\src\main\resources\models\model.pmml risk-classifier-rest-v1 true
```

说明：

- 会依次 Maven 安装、弹出多个 CMD 窗口（API、Python 模型服务、Flink 作业、Socket、mock 客户端等），**不要全部关掉**；具体数量以脚本为准。
- **本机 JDK / Maven**：优先使用已在 PATH 中的 `java` / `mvn`；若已在 `.env` 中设置 `JAVA_HOME`，脚本启动前会加载 `.env`，请保证该 JDK 存在 `bin\java.exe`。
- 若 Maven 报错，先在本机配置好 `JAVA_HOME` 与 `mvn`，再重试。

---

## 13. 验证任务 C（Webhook）

1. 启动 API 后，用 Postman / curl 管理 Webhook：  
   `GET http://localhost:8080/api/webhooks`  
   `POST http://localhost:8080/api/webhooks`（JSON 体字段见 [`c-observability.md`](c-observability.md)）
2. 联调（不外呼）：  
   `POST http://localhost:8080/api/webhooks/{id}/test?dryRun=true`
3. 发送记录：  
   `GET http://localhost:8080/api/webhooks/{id}/deliveries?page=0&size=20`

Flink 告警 sink 会读取库中 `enabled=1` 的 `webhook_config`；仅配置 `ZEPHYR_ALERT_WEBHOOK_URL` 时见 `c-observability.md` §1.3。

---

## 14. 常见问题

| 现象 | 处理 |
|------|------|
| Grafana 无数据源 / 无「Zephyr Watch」文件夹 | 使用 `zephyr-dashboard/docker-compose.grafana.yml` 启动，并检查 `zephyr-dashboard/grafana/provisioning` 是否挂进容器 |
| 业务大盘 SQL 报错连不上库 | 检查 `ZEPHYR_GRAFANA_MYSQL_*` 与 `mysql.yml` 中 `url`（Docker 访问宿主机用 `host.docker.internal`） |
| 系统大盘无曲线 | 本机需单独运行 Prometheus 且 `prometheus.yml` 中 `zephyr-api` 的 `targets` 能指到 API；未启 Flink/Kafka exporter 时部分面板为空属正常 |
| Webhook 测试 502 | 多为网络/防火墙；先用 `dryRun=true` 校验 JSON |
| Flink 连不上 Kafka | `ZEPHYR_KAFKA_BOOTSTRAP_SERVERS` 与虚拟机 `advertised.listeners` 一致，且本机到 broker 网络通 |

更细的指标名与答辩演示顺序仍以 **[`c-observability.md`](c-observability.md)** 为准。
