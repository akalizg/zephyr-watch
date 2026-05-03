# Zephyr Dashboard

Grafana assets for the Zephyr-Watch business dashboard.

Provision the MySQL datasource from `grafana/provisioning/datasources/mysql.yml`, then import `grafana/dashboards/zephyr-watch-business.json`.

Set these environment variables for Grafana when the defaults do not match your machine:

```bash
ZEPHYR_GRAFANA_MYSQL_URL=host.docker.internal:3306
ZEPHYR_GRAFANA_MYSQL_DATABASE=zephyr_watch
ZEPHYR_GRAFANA_MYSQL_USER=root
ZEPHYR_GRAFANA_MYSQL_PASSWORD=your_password
```
