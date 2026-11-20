#!/usr/bin/env python3
"""LAN read-only angle gateway. Never imports VoiceSep or forwards control requests."""

from __future__ import annotations

import argparse
import ipaddress
import json
import math
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

PROTOCOL = "voicesep-angle-v1"
MAX_RESPONSE = 256 * 1024


def finite(value):
    try:
        return value if type(value) in (int, float) and math.isfinite(value) else None
    except OverflowError:
        return None


def nonnegative(value):
    value = finite(value)
    return value if value is not None and value >= 0 else None


def normalize(state, *, source="voicesep", fetch_ms=0.0):
    if not isinstance(state, dict):
        raise TypeError("Expected an object from /api/state")
    result = {
        "protocol": PROTOCOL,
        "read_only": True,
        "capture_clock_aligned": False,
        "source": source,
        "upstream_ok": True,
        "angle_convention": "array_x_zero_ccw",
        "active": state.get("active") is True,
        "valid": False,
        "azimuth_deg": None,
        "run_id": state.get("run_id") if isinstance(state.get("run_id"), str) else None,
        "epoch": state.get("epoch") if type(state.get("epoch")) is int else 0,
        "sequence": state.get("sequence") if type(state.get("sequence")) is int else 0,
        "reason": str(state.get("reason", "unknown"))[:160],
        "configured_update_ms": nonnegative(state.get("configured_update_ms")),
        "gateway_fetch_ms": round(fetch_ms, 2),
    }
    for key in (
        "result_age_ms",
        "input_age_ms",
        "last_valid_age_ms",
        "backlog_ms",
        "processing_ms",
        "rtf",
        "update_hz",
        "audio_sample",
    ):
        result[key] = nonnegative(state.get(key))
    period = result["configured_update_ms"]
    if period is not None and not 5 <= period <= 5000:
        result["configured_update_ms"] = None
    angle = finite(state.get("azimuth_deg"))
    valid = (
        result["active"]
        and state.get("valid") is True
        and angle is not None
        and 0 <= angle < 360
        and bool(result["run_id"])
        and result["epoch"] >= 0
        and result["sequence"] >= 0
        and result["result_age_ms"] is not None
        and result["input_age_ms"] is not None
    )
    result["valid"] = bool(valid)
    result["azimuth_deg"] = angle if valid else None
    if state.get("valid") is True and not valid:
        result["reason"] = "invalid_upstream_fields"
    # Deliberately exclude paths, credentials, audio, start/stop/config APIs and raw candidate details.
    return result


def unavailable(reason):
    result = normalize({"active": False, "valid": False, "reason": reason})
    result["upstream_ok"] = False
    return result


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class StateProvider:
    def __init__(
        self,
        upstream="http://127.0.0.1:8011",
        demo=False,
        period_ms=128,
        synthetic_audio=False,
    ):
        parsed = urlsplit(upstream)
        if (
            parsed.scheme != "http"
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.path not in ("", "/")
            or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
        ):
            raise ValueError(
                "Upstream must be a loopback HTTP origin, for example http://127.0.0.1:8011"
            )
        if not 5 <= period_ms <= 5000:
            raise ValueError("Demo period must be 5..5000 ms")
        self.upstream = upstream.rstrip("/")
        self.demo = demo
        self.synthetic_audio = synthetic_audio
        self.period_ms = period_ms
        self.started = time.monotonic()
        self.run_id = "demo-" + uuid.uuid4().hex[:12]
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def get(self):
        if self.demo:
            elapsed = (time.monotonic() - self.started) * 1000
            sequence = int(elapsed // self.period_ms)
            return normalize(
                {
                    "active": True,
                    "valid": True,
                    "azimuth_deg": (35 + sequence * self.period_ms / 1000 * 12) % 360,
                    "run_id": self.run_id,
                    "epoch": 0,
                    "sequence": sequence,
                    "configured_update_ms": self.period_ms,
                    "reason": "synthetic_angle_demo",
                    "result_age_ms": elapsed - sequence * self.period_ms,
                    "input_age_ms": 0,
                    "last_valid_age_ms": 0,
                    "backlog_ms": 0,
                    "update_hz": 1000 / self.period_ms,
                },
                source="demo",
            )
        before = time.monotonic()
        try:
            request = Request(
                self.upstream + "/api/state",
                headers={"Cache-Control": "no-cache", "Accept": "application/json"},
            )
            with self.opener.open(request, timeout=1.5) as response:
                body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                return unavailable("upstream_response_too_large")
            return normalize(
                json.loads(body),
                source="synthetic_audio" if self.synthetic_audio else "voicesep",
                fetch_ms=(time.monotonic() - before) * 1000,
            )
        except (HTTPError, URLError, OSError, ValueError, TypeError):
            return unavailable("upstream_unavailable_or_invalid")


def make_server(host, port, provider):
    viewer = Path(__file__).with_name("viewer.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def send(self, status, body, content_type="application/json; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store, max-age=0")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            self.close_connection = True

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/":
                self.send(200, viewer, "text/html; charset=utf-8")
            elif path == "/health":
                self.send(
                    200,
                    json.dumps(
                        {
                            "service": "voicesep-readonly-gateway",
                            "protocol": PROTOCOL,
                            "demo": provider.demo,
                        }
                    ).encode(),
                )
            elif path == "/api/state":
                self.send(
                    200,
                    json.dumps(
                        provider.get(), ensure_ascii=False, allow_nan=False
                    ).encode("utf-8"),
                )
            else:
                self.send(404, b'{"error":"read_only_endpoint_not_found"}')

        do_HEAD = do_GET

        def do_POST(self):
            self.send(405, b'{"error":"read_only_gateway"}')

        do_PUT = do_POST
        do_DELETE = do_POST

        def log_message(self, format, *args):
            return

    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8014)
    parser.add_argument("--upstream", default="http://127.0.0.1:8011")
    parser.add_argument(
        "--demo",
        action="store_true",
        help="Explicit synthetic ANGLES; no sound recognition",
    )
    parser.add_argument(
        "--synthetic-audio",
        action="store_true",
        help="Label an isolated upstream processing synthetic 13-channel audio",
    )
    parser.add_argument(
        "--period-ms",
        type=int,
        default=128,
        help="Demo only; real period follows configured_update_ms",
    )
    args = parser.parse_args()
    ipaddress.ip_address(args.bind)
    provider = StateProvider(
        args.upstream, args.demo, args.period_ms, args.synthetic_audio
    )
    with make_server(args.bind, args.port, provider) as server:
        print(
            f"Read-only angle viewer: http://{args.bind}:{server.server_port}",
            flush=True,
        )
        print(
            "SYNTHETIC ANGLE DEMO"
            if args.demo
            else "Reads DJ_Drone /api/state; no flight or acquisition control",
            flush=True,
        )
        try:
            server.serve_forever(poll_interval=0.2)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
