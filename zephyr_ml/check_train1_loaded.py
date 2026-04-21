import argparse
import json
import time
from pathlib import Path

import pandas as pd
from pyhive import hive

BASELINE_JSON = Path("data/artifacts/train1_baseline.json")
BASELINE_CSV = Path("data/artifacts/train1_per_machine.csv")

HIVE_HOST = "192.168.88.161"
HIVE_PORT = 10000
HIVE_USERNAME = "root"
HIVE_DATABASE = "zephyr_dw"

SQL_TOTAL = """
SELECT
    COUNT(*) AS total_rows,
    COUNT(DISTINCT machine_id) AS machine_count
FROM dwd_sensor_clean
"""

SQL_PER_MACHINE = """
SELECT
    machine_id,
    COUNT(*) AS row_cnt,
    MAX(cycle) AS max_cycle
FROM dwd_sensor_clean
GROUP BY machine_id
ORDER BY machine_id
"""

def load_baseline():
    with open(BASELINE_JSON, "r", encoding="utf-8") as f:
        baseline = json.load(f)
    per_machine = pd.read_csv(BASELINE_CSV)
    return baseline, per_machine

def query_hive():
    conn = hive.Connection(
        host=HIVE_HOST,
        port=HIVE_PORT,
        username=HIVE_USERNAME,
        database=HIVE_DATABASE
    )
    try:
        total_df = pd.read_sql(SQL_TOTAL, conn)
        machine_df = pd.read_sql(SQL_PER_MACHINE, conn)
    finally:
        conn.close()
    return total_df.iloc[0].to_dict(), machine_df

def check_once():
    baseline, expected_machine_df = load_baseline()
    total_info, actual_machine_df = query_hive()

    expected_total_rows = int(baseline["expected_total_rows"])
    expected_machine_count = int(baseline["expected_machine_count"])

    actual_total_rows = int(total_info["total_rows"])
    actual_machine_count = int(total_info["machine_count"])

    merged = expected_machine_df.merge(
        actual_machine_df,
        on="machine_id",
        how="outer",
        suffixes=("_expected", "_actual")
    ).sort_values("machine_id")

    merged["row_ok"] = merged["row_cnt_expected"] == merged["row_cnt_actual"]
    merged["cycle_ok"] = merged["max_cycle_expected"] == merged["max_cycle_actual"]

    total_ok = actual_total_rows == expected_total_rows
    machine_ok = actual_machine_count == expected_machine_count
    per_machine_ok = bool((merged["row_ok"].fillna(False) & merged["cycle_ok"].fillna(False)).all())

    print("=== train1 传输检查 ===")
    print(f"期望总行数: {expected_total_rows} | Hive当前总行数: {actual_total_rows}")
    print(f"期望设备数: {expected_machine_count} | Hive当前设备数: {actual_machine_count}")
    print(f"总行数匹配: {total_ok}")
    print(f"设备数匹配: {machine_ok}")
    print(f"逐设备 row_cnt/max_cycle 匹配: {per_machine_ok}")

    if not per_machine_ok:
        bad = merged[~(merged["row_ok"].fillna(False) & merged["cycle_ok"].fillna(False))]
        print("\n=== 不匹配设备（前20行）===")
        print(bad.head(20).to_string(index=False))

    finished = total_ok and machine_ok and per_machine_ok
    print(f"\n最终结论: {'train1 已完整传入 Hive' if finished else 'train1 尚未传完'}")
    return finished

def main():
    parser = argparse.ArgumentParser(description="检查 train_FD001 是否已完整进入 Hive")
    parser.add_argument("--watch", action="store_true", help="持续轮询直到传完")
    parser.add_argument("--interval", type=int, default=10, help="轮询间隔秒数")
    args = parser.parse_args()

    if not args.watch:
        check_once()
        return

    while True:
        finished = check_once()
        if finished:
            break
        time.sleep(args.interval)

if __name__ == "__main__":
    main()