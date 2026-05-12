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

HIVE_HOST = "node1.itcast.cn"  # 改成你的虚拟机 IP
HIVE_PORT = 10000
HIVE_USERNAME = "root"
HIVE_DATABASE = "zephyr_dw"

SQL = """
      SELECT machine_id, \
             window_start, \
             window_end, \
             sample_count, \
             cycle_start, \
             cycle_end, \
             pressure_min, \
             pressure_max, \
             pressure_avg, \
             pressure_std, \
             pressure_trend, \
             temperature_min, \
             temperature_max, \
             temperature_avg, \
             temperature_std, \
             temperature_trend, \
             speed_min, \
             speed_max, \
             speed_avg, \
             speed_std, \
             speed_trend
      FROM dws_device_feature \
      """


def build_labels(df: pd.DataFrame, risk_threshold: int) -> pd.DataFrame:
    df = df.copy()
    df["max_cycle"] = df.groupby("machine_id")["cycle_end"].transform("max")
    df["rul"] = df["max_cycle"] - df["cycle_end"]
    df["risk_label"] = (df["rul"] <= risk_threshold).astype(int)
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
        print(f"[INFO] 查询到 {len(df)} 行数据")
    finally:
        conn.close()

    if df.empty:
        raise ValueError("Hive 查询结果为空，请先检查 dws_device_feature 是否已有数据")

    df = build_labels(df, args.risk_threshold)

    # 使用下划线列名过滤
    keep_cols = [c.replace('machineId', 'machine_id')
                 .replace('windowStart', 'window_start')
                 .replace('windowEnd', 'window_end')
                 .replace('sampleCount', 'sample_count')
                 .replace('cycleStart', 'cycle_start')
                 .replace('cycleEnd', 'cycle_end')
                 .replace('pressureMin', 'pressure_min')
                 .replace('pressureMax', 'pressure_max')
                 .replace('pressureAvg', 'pressure_avg')
                 .replace('pressureStd', 'pressure_std')
                 .replace('pressureTrend', 'pressure_trend')
                 .replace('temperatureMin', 'temperature_min')
                 .replace('temperatureMax', 'temperature_max')
                 .replace('temperatureAvg', 'temperature_avg')
                 .replace('temperatureStd', 'temperature_std')
                 .replace('temperatureTrend', 'temperature_trend')
                 .replace('speedMin', 'speed_min')
                 .replace('speedMax', 'speed_max')
                 .replace('speedAvg', 'speed_avg')
                 .replace('speedStd', 'speed_std')
                 .replace('speedTrend', 'speed_trend')
                 .replace('rul', 'rul')
                 .replace('riskLabel', 'risk_label')
                 for c in META_COLUMNS + FEATURE_COLUMNS + TARGET_COLUMNS]

    keep_cols = [c for c in keep_cols if c in df.columns]
    df = df[keep_cols].sort_values(["machine_id", "cycle_end", "window_start"]).reset_index(drop=True)

    output_dir = os.path.dirname(args.output)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    df.to_csv(args.output, index=False, encoding="utf-8")
    print(f"[INFO] 训练数据集已导出: {args.output}")
    print(f"[INFO] 样本数: {len(df)}")
    print(f"[INFO] 机器数: {df['machine_id'].nunique()}")
    print(f"[INFO] 高风险样本数: {int(df['risk_label'].sum())}")
    print(f"[INFO] 低风险样本数: {int((1 - df['risk_label']).sum())}")


if __name__ == "__main__":
    main()