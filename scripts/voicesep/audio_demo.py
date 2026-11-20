#!/usr/bin/env python3
"""Isolated synthetic WAV -> real VoiceSep algorithm -> read-only LAN gateway test. No hardware."""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener

PROJECT = Path(__file__).resolve().parents[2]
OPENER = build_opener(ProxyHandler({}))


def request(port, path, data=None):
    body = None if data is None else json.dumps(data).encode()
    req = Request(
        f"http://127.0.0.1:{port}{path}",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with OPENER.open(req, timeout=2) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--voicesep-root", type=Path, default=PROJECT.parent / "VoiceSep"
    )
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--angle", type=float, default=45)
    parser.add_argument("--hop", type=int, default=256)
    parser.add_argument("--step", type=int, default=8)
    parser.add_argument("--backend-port", type=int, default=18011)
    parser.add_argument("--gateway-port", type=int, default=18014)
    args = parser.parse_args()
    if not 3 <= args.seconds <= 300:
        parser.error("seconds must be 3..300")
    root = args.voicesep_root.resolve()
    python = root / ".venv/bin/python"
    # Re-execute with the isolated VoiceSep environment; no system packages are installed.
    if Path(sys.prefix).resolve() != (root / ".venv").resolve():
        raise SystemExit(
            subprocess.call(
                [str(python), "-B", str(Path(__file__).resolve()), *sys.argv[1:]]
            )
        )
    for name in ("OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "OMP_NUM_THREADS"):
        os.environ[name] = "1"
    sys.path.insert(0, str(root))
    from research.DJ_Drone.config import Controls
    from research.DJ_Drone.simulate import SimulatedWriter, synthesize

    controls = Controls(hop=args.hop, localization_step=args.step)
    period_ms = controls.processing("single").update_seconds * 1000
    for port in (args.backend_port, args.gateway_port):
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Port {port} is occupied; no service was stopped")
    directory = (
        root / "research/DJ_Drone/tmp" / ("android-link-" + uuid.uuid4().hex[:10])
    )
    directory.mkdir(parents=True)
    signal = synthesize(args.seconds, args.angle, 0, "broadband")
    writer = SimulatedWriter(directory / "input")
    processes = []
    logs = []
    observations = []
    try:
        specs = [
            (
                "backend",
                [
                    str(python),
                    "-B",
                    str(root / "research/DJ_Drone/app.py"),
                    "--port",
                    str(args.backend_port),
                    "--runtime-dir",
                    str(directory / "runtime"),
                ],
            ),
            (
                "gateway",
                [
                    str(python),
                    "-B",
                    str(PROJECT / "scripts/voicesep/gateway.py"),
                    "--bind",
                    "0.0.0.0",
                    "--port",
                    str(args.gateway_port),
                    "--upstream",
                    f"http://127.0.0.1:{args.backend_port}",
                    "--synthetic-audio",
                ],
            ),
        ]
        for name, command in specs:
            log = (directory / (name + ".log")).open("wb")
            logs.append(log)
            processes.append(
                subprocess.Popen(
                    command, cwd=root, stdout=log, stderr=subprocess.STDOUT
                )
            )
        deadline = time.monotonic() + 20
        while True:
            try:
                request(args.backend_port, "/api/state")
                request(args.gateway_port, "/health")
                break
            except OSError:
                if time.monotonic() >= deadline or any(
                    p.poll() is not None for p in processes
                ):
                    raise RuntimeError(f"Demo startup failed; see {directory}")
                time.sleep(0.2)
        request(
            args.backend_port,
            "/api/start",
            {
                "watch_dir": str(directory / "input"),
                "mode": "single",
                "controls": controls.model_dump(),
                "beamforming": False,
            },
        )
        print(
            f"SYNTHETIC AUDIO / no array or drone. Expected angle {args.angle} degrees, configured period {period_ms} ms.",
            flush=True,
        )
        print(
            f"Viewer: http://127.0.0.1:{args.gateway_port} (phone: use this Mac's LAN IP with port {args.gateway_port})",
            flush=True,
        )
        print(f"Duration {args.seconds}s; generated data: {directory}", flush=True)
        started = time.monotonic()
        last_identity = None
        for offset in range(0, signal.shape[1], 1024):
            writer.append(signal[:, offset : offset + 1024])
            result = request(args.gateway_port, "/api/state")
            identity = (
                result.get("run_id"),
                result.get("epoch"),
                result.get("sequence"),
            )
            if result.get("valid") and identity != last_identity:
                observations.append(result["azimuth_deg"])
                last_identity = identity
                if len(observations) == 1:
                    print(
                        "First valid algorithm angle:",
                        result["azimuth_deg"],
                        "degrees",
                        flush=True,
                    )
                if abs(result["configured_update_ms"] - period_ms) > 1e-6:
                    raise AssertionError(
                        "Output update period does not match algorithm settings"
                    )
            deadline = started + min(offset + 1024, signal.shape[1]) / 16000
            time.sleep(max(0, deadline - time.monotonic()))
        writer.close()
        time.sleep(1.2)
        stale = request(args.gateway_port, "/api/state")
        if stale["valid"]:
            raise AssertionError("Completed synthetic input remained valid")
        request(args.backend_port, "/api/stop", {})
        errors = sorted(
            abs((angle - args.angle + 180) % 360 - 180) for angle in observations
        )
        if not errors or errors[len(errors) // 2] > 8:
            raise AssertionError(
                "Synthetic source direction was not localized reliably"
            )
        print(
            f"PASS: {len(errors)} unique valid results, median circular error {errors[len(errors) // 2]:.2f} degrees; input stall correctly invalidated the result.",
            flush=True,
        )
    finally:
        writer.close()
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
