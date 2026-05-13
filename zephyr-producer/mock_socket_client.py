import csv
import json
import os
import socket
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / "zephyr-ml-2" / "training_data.csv"

SOCKET_HOST = os.environ.get("ZEPHYR_SOCKET_HOST", "127.0.0.1")
SOCKET_PORT = int(os.environ.get("ZEPHYR_SOCKET_PORT", "9999"))
SEND_INTERVAL_SEC = float(os.environ.get("ZEPHYR_SOCKET_SEND_INTERVAL_SEC", "0.5"))
CONNECT_RETRY_SEC = float(os.environ.get("ZEPHYR_SOCKET_CONNECT_RETRY_SEC", "2"))


def row_to_sensor_json(row: dict) -> str:
    payload = {
        "machineId": int(row["machine_id"]),
        "cycle": int(float(row.get("cycle_end") or 0)),
        "pressure": float(row["pressure_avg"]),
        "temperature": float(row["temperature_avg"]),
        "speed": float(row["speed_avg"]),
        "eventTime": int(time.time() * 1000),
    }
    return json.dumps(payload, separators=(",", ":"))


def connect_with_retry() -> socket.socket:
    while True:
        try:
            client = socket.create_connection((SOCKET_HOST, SOCKET_PORT), timeout=10)
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print(f"Connected to SensorSocketServer at {SOCKET_HOST}:{SOCKET_PORT}")
            return client
        except OSError as exc:
            print(f"Socket connect failed: {exc}. Retrying in {CONNECT_RETRY_SEC}s...")
            time.sleep(CONNECT_RETRY_SEC)


def send_line(client: socket.socket, line: str) -> socket.socket:
    payload = (line + "\n").encode("utf-8")
    while True:
        try:
            client.sendall(payload)
            return client
        except OSError as exc:
            print(f"Socket send failed: {exc}. Reconnecting...")
            try:
                client.close()
            except OSError:
                pass
            client = connect_with_retry()


def main() -> None:
    if not CSV_PATH.exists():
        raise FileNotFoundError(f"Training data file not found: {CSV_PATH}")

    print(f"Streaming sensor data to SensorSocketServer: {SOCKET_HOST}:{SOCKET_PORT}")
    client = connect_with_retry()

    try:
        with CSV_PATH.open("r", encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f)
            for idx, row in enumerate(reader, start=1):
                msg = row_to_sensor_json(row)
                client = send_line(client, msg)

                if idx % 20 == 0:
                    print(f"Sent {idx} messages. Latest: {msg}")

                time.sleep(SEND_INTERVAL_SEC)
    finally:
        try:
            client.close()
        except OSError:
            pass


if __name__ == "__main__":
    main()
