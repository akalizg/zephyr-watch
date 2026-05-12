import csv
import json
import time
from pathlib import Path
from kafka import KafkaProducer  # 切换到 Kafka 库

ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / "zephyr-ml-2" / "training_data.csv"

# 修改为你的 Kafka 地址和 Topic
KAFKA_BOOTSTRAP_SERVERS = "node1:9092"
TOPIC_NAME = "sensor_raw_topic"
SEND_INTERVAL_SEC = 0.5


def row_to_sensor_json(row: dict) -> str:
    # 保持原有解析逻辑
    payload = {
        "machineId": int(row["machine_id"]),
        "cycle": int(float(row.get("cycle_end") or 0)),
        "pressure": float(row["pressure_avg"]),
        "temperature": float(row["temperature_avg"]),
        "speed": float(row["speed_avg"]),
        "eventTime": int(time.time() * 1000)  # 必须使用当前系统毫秒时间戳
    }
    return json.dumps(payload, separators=(",", ":"))


def main() -> None:
    if not CSV_PATH.exists():
        raise FileNotFoundError(f"文件不存在: {CSV_PATH}")

    # 初始化 Kafka 生产者
    producer = KafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        value_serializer=lambda v: v.encode('utf-8')
    )

    print(f"开始发送数据到 Kafka: {TOPIC_NAME} ...")

    with CSV_PATH.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for idx, row in enumerate(reader, start=1):
            msg = row_to_sensor_json(row)

            # 发送至 Kafka
            producer.send(TOPIC_NAME, value=msg)

            if idx % 20 == 0:
                print(f"已发送 {idx} 条消息: {msg}")
                producer.flush()  # 确保送达

            time.sleep(SEND_INTERVAL_SEC)


if __name__ == "__main__":
    main()