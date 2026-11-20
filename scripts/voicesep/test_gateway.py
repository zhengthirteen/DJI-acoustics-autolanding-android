import importlib.util
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

spec = importlib.util.spec_from_file_location(
    "gateway", Path(__file__).with_name("gateway.py")
)
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


def sample():
    return {
        "active": True,
        "valid": True,
        "azimuth_deg": 45.5,
        "run_id": "run-a",
        "epoch": 0,
        "sequence": 1,
        "configured_update_ms": 128,
        "result_age_ms": 0,
        "input_age_ms": 0,
        "backlog_ms": 0,
        "config": {"watch_dir": "PRIVATE_PATH"},
        "output_dir": "PRIVATE_OUTPUT",
        "reason": "tracking",
    }


class GatewayTests(unittest.TestCase):
    def test_only_public_angle_fields_are_forwarded(self):
        result = gateway.normalize(sample())
        self.assertTrue(result["valid"])
        self.assertEqual(result["configured_update_ms"], 128)
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertTrue(result["read_only"])
        self.assertFalse(result["capture_clock_aligned"])

    def test_invalid_source_is_not_replaced_by_zero_angle(self):
        state = sample()
        state.update(valid=False, azimuth_deg=0)
        self.assertIsNone(gateway.normalize(state)["azimuth_deg"])
        state = sample()
        state["azimuth_deg"] = float("nan")
        self.assertFalse(gateway.normalize(state)["valid"])

    def test_period_is_read_from_source_not_hardcoded(self):
        for period in (64, 96, 128, 256, 768):
            state = sample()
            state["configured_update_ms"] = period
            self.assertEqual(gateway.normalize(state)["configured_update_ms"], period)

    def test_non_loopback_upstream_and_demo_are_explicit(self):
        with self.assertRaises(ValueError):
            gateway.StateProvider("http://example.com:80")
        value = gateway.StateProvider(demo=True, period_ms=96).get()
        self.assertEqual(value["source"], "demo")
        self.assertEqual(value["configured_update_ms"], 96)

    def test_real_http_read_and_control_routes_are_blocked(self):
        state = sample()

        class Upstream(BaseHTTPRequestHandler):
            def do_GET(self):
                data = json.dumps(state).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        proxy = gateway.make_server(
            "127.0.0.1",
            0,
            gateway.StateProvider(f"http://127.0.0.1:{upstream.server_port}"),
        )
        threads = [
            threading.Thread(target=s.serve_forever, daemon=True)
            for s in (upstream, proxy)
        ]
        for thread in threads:
            thread.start()
        try:
            origin = f"http://127.0.0.1:{proxy.server_port}"
            with urlopen(origin + "/api/state", timeout=2) as response:
                result = json.load(response)
            self.assertTrue(result["valid"])
            self.assertEqual(result["azimuth_deg"], 45.5)
            with self.assertRaises(HTTPError) as rejected:
                urlopen(
                    Request(origin + "/api/start", data=b"{}", method="POST"), timeout=2
                )
            self.assertEqual(rejected.exception.code, 405)
            with self.assertRaises(HTTPError) as rejected:
                urlopen(origin + "/api/runs/private", timeout=2)
            self.assertEqual(rejected.exception.code, 404)
            state.update(active=False, valid=False)
            with urlopen(origin + "/api/state", timeout=2) as response:
                self.assertFalse(json.load(response)["valid"])
        finally:
            for server in (proxy, upstream):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
