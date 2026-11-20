#!/usr/bin/env python3
"""Manage only the three local processes created for this VoiceSep integration."""

from __future__ import annotations

import argparse
import calendar
import json
import os
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

from diagnostics import diagnose
from local_credentials import CredentialsError, load_into_smd

PROJECT = Path(__file__).resolve().parents[2]
STATE_DIR = PROJECT / "artifacts" / "voicesep-stack"
STATE_FILE = STATE_DIR / "processes.json"
SERVICES = {"smd": 8012, "direction": 8011, "gateway": 8014}
MONTHS = [
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
]


def process_field(pid, field, *, utc=False):
    if type(pid) is not int or pid <= 0:
        return ""
    environment = os.environ.copy()
    # ps date text is locale-dependent; terminal language must not change ownership.
    environment["LC_ALL"] = "C"
    environment["LANG"] = "C"
    if utc:
        environment["TZ"] = "UTC"
    else:
        environment.pop("TZ", None)
    return subprocess.run(
        ["ps", "-ww", "-p", str(pid), "-o", field + "="],
        capture_output=True,
        text=True,
        check=False,
        env=environment,
    ).stdout.strip()


def command(pid):
    return process_field(pid, "command")


def created(pid):
    return process_field(pid, "lstart")


def date_parts(text):
    """Read existing English/Chinese records without changing Python's locale."""
    try:
        fields = text.split()
        if len(fields) == 5 and fields[1] in MONTHS:
            _, month, day, clock, year = fields
            month = MONTHS.index(month) + 1
        elif len(fields) == 4 and re.fullmatch(r"\d+月/\d+", fields[1]):
            _, month_day, clock, year = fields
            month, day = month_day.replace("月", "").split("/")
        else:
            return None
        hour, minute, second = map(int, clock.split(":"))
        value = datetime(int(year), int(month), int(day), hour, minute, second)
        return value
    except (AttributeError, TypeError, ValueError):
        return None


def started_epoch(pid):
    value = date_parts(process_field(pid, "lstart", utc=True))
    return calendar.timegm(value.timetuple()) if value else None


def ours(item):
    pid = item.get("pid")
    marker = item.get("marker")
    if type(pid) is not int or pid <= 0 or not isinstance(marker, str) or not marker:
        return False
    if "started_epoch_utc" in item:
        expected = item["started_epoch_utc"]
        same_start = type(expected) is int and expected == started_epoch(pid)
    else:
        # Migrate only an already-verified legacy identity, never just an occupied port.
        expected = date_parts(item.get("created"))
        same_start = expected is not None and expected == date_parts(created(pid))
    return same_start and marker in command(pid)


def listening_pids(port):
    try:
        result = subprocess.run(
            ["lsof", "-nP", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return []
    return sorted(
        {int(line) for line in result.stdout.splitlines() if line.isdecimal()}
    )


def save_state(entries):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w", dir=STATE_DIR, prefix="processes-", suffix=".tmp", delete=False
    ) as output:
        temporary = Path(output.name)
        output.write(json.dumps(entries, indent=2))
    try:
        temporary.replace(STATE_FILE)
    finally:
        temporary.unlink(missing_ok=True)


def upgrade_identities(entries):
    result = [dict(entry) for entry in entries]
    for entry in result:
        if "started_epoch_utc" not in entry and ours(entry):
            epoch = started_epoch(entry["pid"])
            if epoch is not None:
                entry["started_epoch_utc"] = epoch
    if result != entries:
        save_state(result)


def occupied(port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return False
        except OSError:
            return True


def read_state():
    entries = json.loads(STATE_FILE.read_text()) if STATE_FILE.exists() else []
    if not isinstance(entries, list) or any(
        not isinstance(item, dict) or item.get("name") not in SERVICES
        for item in entries
    ):
        raise RuntimeError("进程记录格式异常，未接管或停止任何服务。")
    return entries


def wait_ready(port, route, process):
    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Service exited on port {port}; inspect {STATE_DIR}")
        try:
            with opener.open(f"http://127.0.0.1:{port}{route}", timeout=1) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"Service on {port} did not become ready")


def start(root):
    previous = read_state()
    if len(previous) == 3 and all(ours(item) for item in previous):
        upgrade_identities(previous)
        print("三个服务已在运行，已复用；没有关闭或重启进程。")
        load_credentials()
        status()
        return
    if any(ours(item) for item in previous):
        raise RuntimeError(
            "This stack is already running; use status. No existing service was stopped."
        )
    python = root / ".venv/bin/python"
    if not python.exists():
        raise RuntimeError("Create VoiceSep/.venv and install requirements.txt first")
    ports = (8012, 8011, 8014)
    conflicts = [port for port in ports if occupied(port)]
    if conflicts:
        details = "; ".join(
            f"端口 {port}，监听 PID {listening_pids(port) or '未知'}"
            for port in conflicts
        )
        raise RuntimeError(
            f"{details}。端口已占用但未通过本工具进程身份核对；请运行 status 查看。未结束任何进程。"
        )
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    specs = [
        (
            "smd",
            8012,
            "/api/status",
            [
                str(python),
                "-B",
                "-m",
                "uvicorn",
                "app:app",
                "--app-dir",
                str(root / "research/SMD"),
                "--host",
                "127.0.0.1",
                "--port",
                "8012",
            ],
        ),
        (
            "direction",
            8011,
            "/api/state",
            [
                str(python),
                "-B",
                str(root / "research/DJ_Drone/app.py"),
                "--port",
                "8011",
            ],
        ),
        (
            "gateway",
            8014,
            "/health",
            [
                str(python),
                "-B",
                str(PROJECT / "scripts/voicesep/gateway.py"),
                "--bind",
                "0.0.0.0",
                "--port",
                "8014",
            ],
        ),
    ]
    entries = []
    try:
        for name, port, route, args in specs:
            logfile = STATE_DIR / (name + ".log")
            environment = os.environ.copy()
            if name == "smd" and sys.platform == "darwin":
                compat = str(PROJECT / "scripts/voicesep/mac_compat")
                environment["PYTHONPATH"] = compat + (
                    os.pathsep + environment["PYTHONPATH"]
                    if environment.get("PYTHONPATH")
                    else ""
                )
                environment["VOICESEP_COMPAT_TMP"] = str(STATE_DIR / "clock-audio")
            with logfile.open("ab", buffering=0) as log:
                process = subprocess.Popen(
                    args,
                    cwd=root,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                    env=environment,
                )
            entry = {
                "name": name,
                "pid": process.pid,
                "created": created(process.pid),
                "marker": str(root / "research/SMD") if name == "smd" else args[2],
                "port": port,
            }
            epoch = started_epoch(process.pid)
            if epoch is not None:
                entry["started_epoch_utc"] = epoch
            entries.append(entry)
            save_state(entries)
            wait_ready(port, route, process)
    except Exception:
        for entry in reversed(entries):
            if ours(entry):
                os.kill(entry["pid"], signal.SIGTERM)
        raise
    load_credentials()
    status()


def load_credentials():
    try:
        loaded, message = load_into_smd()
        print(message)
    except CredentialsError as error:
        print(str(error), file=sys.stderr)
        return False
    return loaded


def status():
    entries = {item["name"]: item for item in read_state()}
    for name, port in SERVICES.items():
        item = entries.get(name)
        if item is not None and ours(item):
            description = f"running (PID {item['pid']}, port {port})"
        elif occupied(port):
            description = f"listening-unverified (port {port}, listening PID {listening_pids(port) or 'unknown'})"
        else:
            description = f"stopped (port {port})"
        print(f"{name}: {description}")
    print_endpoints()


def print_endpoints():
    print("Mac acquisition: http://127.0.0.1:8012")
    print("Mac direction:   http://127.0.0.1:8011")
    print("Read-only view:  http://127.0.0.1:8014")
    result = subprocess.run(
        ["/usr/sbin/ipconfig", "getifaddr", "en0"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode == 0:
        print(
            "Phone candidate URL (same LAN): http://" + result.stdout.strip() + ":8014"
        )
    print(
        "Real array login/calibration is manual in the acquisition page; no hardware was connected automatically."
    )


def stop():
    entries = read_state()
    for entry in reversed(entries):
        if not ours(entry):
            continue
        os.kill(entry["pid"], signal.SIGTERM)
        deadline = time.monotonic() + 20
        while ours(entry) and time.monotonic() < deadline:
            time.sleep(0.2)
        if ours(entry):
            raise RuntimeError(
                f"{entry['name']} is still shutting down; not force-killed"
            )
    status()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action", choices=("start", "status", "stop", "load-credentials", "diagnose")
    )
    parser.add_argument(
        "--voicesep-root", type=Path, default=PROJECT.parent / "VoiceSep"
    )
    args = parser.parse_args()
    if args.action == "start":
        start(args.voicesep_root.resolve())
    elif args.action == "stop":
        stop()
    elif args.action == "load-credentials":
        if not load_credentials():
            raise SystemExit(1)
    elif args.action == "diagnose":
        diagnose()
    else:
        status()


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        print(f"操作未完成：{error}", file=sys.stderr)
        raise SystemExit(1) from None
