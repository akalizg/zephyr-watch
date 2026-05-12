# Zephyr Prometheus

Use `prometheus.yml` to scrape the Spring Boot API, Flink, Kafka exporter, and node exporter.

The Spring Boot API exposes metrics at:

```text
http://localhost:8080/actuator/prometheus
```

Typical local targets:

```text
host.docker.internal:8080  zephyr-api
host.docker.internal:9249  Flink metrics reporter
host.docker.internal:9308  Kafka exporter
host.docker.internal:9100  node exporter
```
