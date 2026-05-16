# Zephyr Dashboard

Grafana 与 Prometheus 资源目录（任务 C 可观测交付物）。**协作同学从零安装（含本目录 Compose、`.env`、Webhook）** 见仓库根目录 **[`docs/setup-collaborators.md`](../docs/setup-collaborators.md)**。

## 1. 快速入口

| 资源 | 路径 |
|------|------|
| Prometheus 抓取配置 | `prometheus/prometheus.yml` |
| 各 job 与 exporter 对齐说明 | `prometheus/exporter-notes/README.md` |
| 业务看板 JSON | `grafana/dashboards/zephyr-watch-business.json` |
| 系统监控看板 JSON | `grafana/dashboards/zephyr-watch-system.json` |
| 数据源与看板自动加载 | `grafana/provisioning/`（`datasources/*.yml`、`dashboards/zephyr.yml`） |
| Webhook / 监控总说明 | 仓库根目录 [`docs/c-observability.md`](../docs/c-observability.md) |

## 2. 启动 Grafana（推荐：Compose + 自动挂载看板）

在本目录执行：

```bash
docker compose -f docker-compose.grafana.yml up -d
```

- 默认 **http://localhost:3000**（`admin` / `admin`，可在 compose 中改）。
- `docker-compose.grafana.yml` 将 `grafana/dashboards` 挂入容器，并由 `grafana/provisioning/dashboards/zephyr.yml` 做 **file provisioning**：启动后 **Zephyr Watch** 文件夹下会出现业务盘与系统盘，**无需每次手动 Import JSON**（除非你使用未挂卷的裸 `docker run`）。
- MySQL 密码等：Compose 通过 `env_file` 读取仓库根 `../.env` 与本目录 `./.env`（后者优先）。勿将真实密码提交到 Git。

Grafana 连接本机 MySQL / Prometheus 时，Docker Desktop 使用 `host.docker.internal`；Linux 上 compose 已配置 `extra_hosts: host.docker.internal:host-gateway`。

## 3. Prometheus（与本机 API / 可选 Exporter）

1. 在本机或容器中启动 Prometheus，加载 `prometheus/prometheus.yml`。
2. 按实际部署修改各 `job` 的 `static_configs.targets`（注释中说明：`host.docker.internal`、`127.0.0.1`、虚拟机 IP 等）。
3. 打开 Prometheus **Status → Targets**，至少保证 **`zephyr-api` 为 UP**（本机已启动 Spring Boot 且暴露 `/actuator/prometheus`）。
4. Flink / Kafka / node 等 job 在未部署对应 exporter 时为 **DOWN** 属正常；系统大盘中对应面板可能无数据。

数据源 UID 与 JSON 内一致：`ZephyrPrometheus`、`ZephyrMySQL`（见 `grafana/provisioning/datasources/` 下 YAML）。

## 4. 仅手动导入 JSON（无 Compose 时）

1. Grafana：**Connections → Data sources**，添加 Prometheus 与 MySQL（URL/账号与实验室一致）。
2. **Dashboards → Import**，上传 `grafana/dashboards/zephyr-watch-business.json` 与 `zephyr-watch-system.json`。
3. 导入时为每个面板选择正确的 Prometheus / MySQL 数据源。

## 5. Grafana 环境变量（覆盖 datasource 默认值）

当默认值与机器不符时，可在 `.env` 或 Compose `environment` 中设置：

```bash
ZEPHYR_GRAFANA_MYSQL_URL=host.docker.internal:3306
ZEPHYR_GRAFANA_MYSQL_DATABASE=zephyr_watch
ZEPHYR_GRAFANA_MYSQL_USER=root
ZEPHYR_GRAFANA_MYSQL_PASSWORD=your_password
```

具体键名以 `grafana/provisioning/datasources/mysql.yml` 内 `env` 引用为准。
