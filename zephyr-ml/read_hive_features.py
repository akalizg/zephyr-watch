import argparse
import os
import sys

import pandas as pd
from pyhive import hive

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(CURRENT_DIR, "data")
if DATA_DIR not in sys.path:
    sys.path.append(DATA_DIR)

from schema import FEATURE_COLUMNS, META_COLUMNS, TARGET_COLUMNS  # noqa: E402

HIVE_HOST = "192.168.88.161"
HIVE_PORT = 10000
HIVE_USERNAME = "root"
HIVE_DATABASE = "zephyr_dw"

SQL = """
SELECT
    machine_id      AS machineId,
    window_start    AS windowStart,
    window_end      AS windowEnd,
    sample_count    AS sampleCount,
    cycle_start     AS cycleStart,
    cycle_end       AS cycleEnd,
    pressure_min    AS pressureMin,
    pressure_max    AS pressureMax,
    pressure_avg    AS pressureAvg,
    pressure_std    AS pressureStd,
    pressure_trend  AS pressureTrend,
    temperature_min AS temperatureMin,
    temperature_max AS temperatureMax,
    temperature_avg AS temperatureAvg,
    temperature_std AS temperatureStd,
    temperature_trend AS temperatureTrend,
    speed_min       AS speedMin,
    speed_max       AS speedMax,
    speed_avg       AS speedAvg,
    speed_std       AS speedStd,
    speed_trend     AS speedTrend
FROM dws_device_feature
"""

def build_labels(df: pd.DataFrame, risk_threshold: int) -> pd.DataFrame:
    df = df.copy()
    df["maxCycle"] = df.groupby("machineId")["cycleEnd"].transform("max")
    df["rul"] = df["maxCycle"] - df["cycleEnd"]
    df["riskLabel"] = (df["rul"] <= risk_threshold).astype(int)
    return df

def main():
    parser = argparse.ArgumentParser(description="从 Hive 导出 Zephyr-Watch 训练数据集")
    parser.add_argument("--output", required=True, help="输出 CSV 路径")
    parser.add_argument("--risk-threshold", type=int, default=30, help="RUL <= 阈值记为高风险")
    args = parser.parse_args()

    conn = hive.Connection(
        host=HIVE_HOST,
        port=HIVE_PORT,
        username=HIVE_USERNAME,
        database=HIVE_DATABASE
    )

    try:
        df = pd.read_sql(SQL, conn)
    finally:
        conn.close()

    if df.empty:
        raise ValueError("Hive 查询结果为空，请先检查 dws_device_feature 是否已有数据")

    df = build_labels(df, args.risk_threshold)

    keep_cols = META_COLUMNS + FEATURE_COLUMNS + TARGET_COLUMNS
    keep_cols = [c for c in keep_cols if c in df.columns]
    df = df[keep_cols].sort_values(["machineId", "cycleEnd", "windowStart"]).reset_index(drop=True)

    output_dir = os.path.dirname(args.output)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    df.to_csv(args.output, index=False, encoding="utf-8")
    print(f"[INFO] 训练数据集已导出: {args.output}")
    print(f"[INFO] 样本数: {len(df)}")
    print(f"[INFO] 机器数: {df['machineId'].nunique()}")
    print(f"[INFO] 高风险样本数: {int(df['riskLabel'].sum())}")
    print(f"[INFO] 低风险样本数: {int((1 - df['riskLabel']).sum())}")

if __name__ == "__main__":
    main()