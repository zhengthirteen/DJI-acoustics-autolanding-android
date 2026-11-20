"""Read-only SMD status and redacted logs; never starts calibration or capture."""

from __future__ import annotations

import argparse
import json
import re
import time
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, build_opener

PROJECT = Path(__file__).resolve().parents[2]
DIRECTORY = PROJECT / "artifacts/voicesep-stack"


def private_values():
    path = PROJECT / "android-sdk-v5-as/secrets.properties"
    values = []
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" not in line or line.lstrip().startswith(("#", "!")):
                continue
            key, value = line.split("=", 1)
            if value and any(
                word in key.upper() for word in ("PASSWORD", "API_KEY", "TOKEN")
            ):
                values.append(value.strip())
    return [value for value in values if value]


def redact(text, secrets=()):
    for secret in sorted(secrets, key=len, reverse=True):
        text = text.replace(secret, "[hidden]")
    return re.sub(
        r"([?&](?:token|password|api_key)=)[^\s&\"']+",
        r"\1[hidden]",
        text,
        flags=re.IGNORECASE,
    )


def get_json(route):
    opener = build_opener(ProxyHandler({}))
    with opener.open("http://127.0.0.1:8012" + route, timeout=3) as response:
        data = response.read(512 * 1024 + 1)
    if len(data) > 512 * 1024:
        raise ValueError("Response too large")
    return json.loads(data)


def report(status, logs, observed_at):
    if not isinstance(status, dict):
        raise TypeError("SMD status must be an object")
    calibration = status.get("clock_calibration")
    state = status.get("state", "unknown")
    lines = [
        f"检查时间：{observed_at}",
        f"SMD 状态：{state}",
        f"说明：{status.get('message', '')}",
        f"设备操作中：{status.get('operation_busy', False)}；设备对象：{status.get('device_present', False)}",
    ]
    if state == "calibrating":
        lines.append("校准正在后台运行；HTTP 200 仅表示请求已接受，请勿重复点击。")
    elif state == "error":
        lines.append("本轮操作失败；看上方说明。设备日志为空也可能是尚未连上 SSH。")
    if isinstance(calibration, dict):
        lines.append(
            f"已有记录：实际 {calibration.get('actual_sample_rate')} Hz / 设备报告 {calibration.get('reported_sample_rate')} Hz"
        )
        lines.append(
            f"校准时间：{calibration.get('calibrated_at')}；方法：{calibration.get('method', '未标明')}"
        )
        lines.append("已有记录不等于本次操作成功；配置/SDK/设备身份仍须匹配。")
    else:
        lines.append("当前没有可显示的校准记录，不能判为校准通过。")
    lines.append(
        f"健康检查：{json.dumps(status.get('health', {}), ensure_ascii=False)}"
    )
    lines.append(
        f"收到帧数：{status.get('received_frames', 0)}；最新帧年龄：{status.get('frame_age_seconds')}"
    )
    entries = (
        logs
        if isinstance(logs, list)
        else logs.get("logs", [])
        if isinstance(logs, dict)
        else []
    )
    lines.append("最近设备日志：")
    if not entries:
        lines.append("  尚无设备日志；SSH连接成功前的错误通常只在状态说明中。")
    for entry in entries[-12:]:
        lines.append(
            "  "
            + (
                json.dumps(entry, ensure_ascii=False)
                if not isinstance(entry, str)
                else entry
            )
        )
    return "\n".join(lines)


def tail_service_log():
    path = DIRECTORY / "smd.log"
    if not path.exists():
        return "尚无服务日志。"
    with path.open("rb") as stream:
        stream.seek(0, 2)
        size = stream.tell()
        stream.seek(max(0, size - 65536))
        text = stream.read().decode("utf-8", errors="replace")
    lines = [
        line
        for line in text.splitlines()
        if not any(
            route in line
            for route in (
                '"GET /api/status ',
                '"GET /api/device/logs ',
                '"GET /api/recordings ',
            )
        )
    ]
    return "\n".join(lines[-15:])


def diagnose(watch_seconds=0):
    secrets = private_values()
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + watch_seconds
    previous = None
    while True:
        now = datetime.now().astimezone().isoformat(timespec="seconds")
        try:
            status = get_json("/api/status")
            logs = get_json("/api/device/logs")
            body = report(status, logs, now)
            fingerprint = json.dumps(
                [
                    status.get("state"),
                    status.get("message"),
                    status.get("operation_busy"),
                    status.get("clock_calibration"),
                    status.get("health"),
                    status.get("received_frames"),
                    logs,
                ],
                ensure_ascii=False,
                sort_keys=True,
            )
        except (HTTPError, URLError, OSError, ValueError, TypeError) as error:
            body = f"检查时间：{now}\n无法读取 SMD 状态：{type(error).__name__}；先运行 stack.py status。"
            fingerprint = body.split("\n", 1)[1]
        if previous != fingerprint:
            safe = redact(body, secrets)
            print(safe, flush=True)
            with (DIRECTORY / "diagnostics.jsonl").open(
                "a", encoding="utf-8"
            ) as stream:
                stream.write(
                    json.dumps({"observed_at": now, "report": safe}, ensure_ascii=False)
                    + "\n"
                )
            previous = fingerprint
        if time.monotonic() >= deadline:
            break
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    print("\n最近服务请求（已隐藏令牌）：\n" + redact(tail_service_log(), secrets))
    print("诊断快照：" + str(DIRECTORY / "diagnostics.jsonl"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--watch",
        type=int,
        default=0,
        help="Read changes for 0..300 seconds; no device commands",
    )
    args = parser.parse_args()
    if not 0 <= args.watch <= 300:
        parser.error("watch must be 0..300 seconds")
    diagnose(args.watch)


if __name__ == "__main__":
    main()
