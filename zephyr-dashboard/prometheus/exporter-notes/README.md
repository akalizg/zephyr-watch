# Prometheus 与 Exporter 对齐说明（任务 C）

本目录说明 `../prometheus.yml` 中各 `job_name` 对应的 **进程、端口与指标来源**，便于在实验室环境（Windows + 虚拟机）把 Targets 调成全部 `UP`。

## zephyr-api（Spring Boot + JVM）

- **路径**：`/actuator/prometheus`（根模块 `zephyr-api` 的 `management.endpoints.web.exposure.include` 已包含 `prometheus`）。
- **常见指标前缀**（Micrometer Prometheus registry，随版本略有差异，以 Prometheus UI → Graph 为准）：
  - `jvm_memory_used_bytes` — 堆/非堆内存（标签 `area`, `id`）
  - `jvm_threads_live_threads`
  - `process_uptime_seconds`
  - `process_cpu_usage`、`system_cpu_usage` — 0~1 的 gauge
  - `http_server_requests_seconds_count` / `_sum` / `_bucket` — HTTP 请求（需有流量后才有 series）
  - `hikaricp_connections_active` — 连接池（若使用 JDBC）
- **抓取地址**：Prometheus 在 Docker Desktop、API 在本机 → `host.docker.internal:8080`；同机 bare metal → `127.0.0.1:8080`。

## flink（9249）

- Flink 需在 `flink-conf.yaml` 中启用 **Prometheus metrics reporter**，JobManager / TaskManager 才会在配置端口暴露指标。
- Dashboard 使用 `flink_jobmanager_job_numberOfCompletedCheckpoints` 等名称；作业运行时长在 Flink 1.17 指标文档中 **`uptime` 已 deprecated，推荐 `runningTime`**，系统大盘面板使用 `flink_jobmanager_job_runningTime or flink_jobmanager_job_uptime` 以兼容不同版本。
- 若集群或 reporter 不同，请在 Prometheus UI 的 Graph 中 **先查询实际 series 名** 再改 Grafana `expr`。
- `prometheus.yml` 中 `metrics_path` 设为 `/`；若你的安装要求显式路径，改为 `/metrics` 并与 reporter 文档一致。

## kafka（9308）

- 该端口一般指 **JMX Exporter** 或 **kafka_exporter**，需在 **运行 Broker 的主机**（例如 `node1`）上部署并监听。
- 若 Kafka 仅在虚拟机 `192.168.x.x` 上，将 `prometheus.yml` 中对应 `targets` 改为 `192.168.x.x:9308`（或实际端口），并保证 Prometheus 容器/进程到该地址 **网络可达**。
- 常见消费 lag 指标：`kafka_consumergroup_lag`（名称随 exporter 略有不同）。

## node（9100）

- **node_exporter** 默认监听 `9100`。若只监控 Linux 虚拟机，将 target 指向虚拟机 IP；Windows 本机通常不跑 node_exporter，该 job 在 Targets 中为 `DOWN` 可接受，或在 `prometheus.yml` 中暂时注释该 job。

## 操作建议

1. 修改 `prometheus.yml` 的 `targets` 后重启 Prometheus。
2. 打开 **Status → Targets**，优先保证 **`zephyr-api` 为 UP**（仅需本机 API 已启动）。
3. Flink / Kafka / node 按实验室是否部署 exporter 决定是否保留对应 job；未部署时面板可能无数据，但不影响 API 相关曲线。
