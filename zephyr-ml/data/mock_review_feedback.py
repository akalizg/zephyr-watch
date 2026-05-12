import json
import time


def build_mock_review_feedback(alert_id="ALERT-xxx"):
    return {
        "alertId": alert_id,
        "reviewLabel": "TRUE_POSITIVE",
        "reviewer": "admin",
        "reviewComment": "现场确认设备存在异常",
        "eventTime": int(time.time() * 1000),
    }


if __name__ == "__main__":
    print(json.dumps(build_mock_review_feedback(), ensure_ascii=False, indent=2))
