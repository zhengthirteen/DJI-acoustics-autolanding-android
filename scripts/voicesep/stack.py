#!/usr/bin/env python3
"""Manage only the three local processes created for this VoiceSep integration."""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

from local_credentials import CredentialsError, load_into_smd

PROJECT = Path(__file__).resolve().parents[2]
STATE_DIR = PROJECT / "artifacts" / "voicesep-stack"
STATE_FILE = STATE_DIR / "processes.json"


def command(pid):
    return subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()


def created(pid):
    return subprocess.run(
        ["ps", "-p", str(pid), "-o", "lstart="],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()


def ours(item):
    return created(item["pid"]) == item["created"] and item["marker"] in command(
        item["pid"]
    )


def occupied(port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return False
        except OSError:
            return True


def read_state():
    return json.loads(STATE_FILE.read_text()) if STATE_FILE.exists() else []


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
    if any(occupied(port) for port in ports):
        raise RuntimeError(
            "A required port is already occupied; no process was stopped"
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
            entries.append(entry)
            STATE_FILE.write_text(json.dumps(entries, indent=2))
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
    for item in read_state():
        print(
            f"{item['name']}: {'running' if ours(item) else 'stopped'} (PID {item['pid']}, port {item['port']})"
        )
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
        "action", choices=("start", "status", "stop", "load-credentials")
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
    else:
        status()


if __name__ == "__main__":
    main()
