import pandas as pd
from pyhive import hive

HIVE_HOST = "192.168.88.161"
HIVE_PORT = 10000
HIVE_USERNAME = "root"
HIVE_DATABASE = "zephyr_dw"

SQL = """
SELECT
    machine_id,
    window_start,
    window_end,
    sample_count,
    cycle_end,
    pressure_min,
    pressure_max,
    pressure_avg,
    pressure_std,
    pressure_trend,
    temperature_min,
    temperature_max,
    temperature_avg,
    temperature_std,
    temperature_trend,
    speed_min,
    speed_max,
    speed_avg,
    speed_std,
    speed_trend
FROM dws_device_feature
LIMIT 20
"""

def main():
    conn = hive.Connection(
        host=HIVE_HOST,
        port=HIVE_PORT,
        username=HIVE_USERNAME,
        database=HIVE_DATABASE
    )

    df = pd.read_sql(SQL, conn)
    conn.close()

    print("读取成功，前 20 行如下：")
    print(df)
    print("\n字段名：")
    print(list(df.columns))
    print("\n样本数：", len(df))

if __name__ == "__main__":
    main()