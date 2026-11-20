import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from local_credentials import CredentialsError, load_into_smd, read_local_credentials

SECRET = "unit-test-only-secret"


class LocalCredentialTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "secrets.properties"
        self.path.write_text(
            "AIRCRAFT_API_KEY=not-forwarded\nSMD_SSH_HOST=192.168.3.102\nSMD_SSH_USERNAME=root\nSMD_SSH_PASSWORD="
            + SECRET
            + "\n"
        )
        self.path.chmod(0o600)
        self.posts = []
        self.bootstrap = {
            "application_id": "smd-array-recorder",
            "token": "test-session-token",
            "status": {
                "host": "192.168.3.102",
                "state": "disconnected",
                "operation_busy": False,
            },
        }
        self.reject_post = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.assertEqual(self.path, "/api/bootstrap")
                body = json.dumps(owner.bootstrap).encode()
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                owner.assertEqual(self.path, "/api/credentials")
                owner.assertEqual(self.headers.get("X-SMD-Token"), "test-session-token")
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.posts.append(body)
                self.send_response(400 if owner.reject_post else 200)
                self.end_headers()
                self.wfile.write(
                    json.dumps(
                        {"detail": SECRET} if owner.reject_post else {"saved": False}
                    ).encode()
                )

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.temporary.cleanup()

    def test_load_uses_original_api_without_starting_device_or_saving_windows_secret(
        self,
    ):
        loaded, message = load_into_smd(self.path, self.origin)
        self.assertTrue(loaded)
        self.assertNotIn(SECRET, message)
        self.assertEqual(
            self.posts, [{"username": "root", "password": SECRET, "remember": False}]
        )
        self.assertNotIn(SECRET, repr(read_local_credentials(self.path)))

    def test_busy_device_is_not_changed(self):
        self.bootstrap["status"]["operation_busy"] = True
        loaded, _ = load_into_smd(self.path, self.origin)
        self.assertFalse(loaded)
        self.assertEqual(self.posts, [])

    def test_active_capture_is_not_changed(self):
        self.bootstrap["status"]["state"] = "recording"
        self.assertFalse(load_into_smd(self.path, self.origin)[0])
        self.assertEqual(self.posts, [])

    def test_wrong_service_or_device_does_not_receive_secret(self):
        self.bootstrap["application_id"] = "another-app"
        with self.assertRaises(CredentialsError):
            load_into_smd(self.path, self.origin)
        self.bootstrap["application_id"] = "smd-array-recorder"
        self.bootstrap["status"]["host"] = "192.168.3.200"
        with self.assertRaises(CredentialsError):
            load_into_smd(self.path, self.origin)
        self.assertEqual(self.posts, [])

    def test_non_loopback_destination_rejected(self):
        with self.assertRaises(CredentialsError):
            load_into_smd(self.path, "http://192.168.3.102:8012")
        self.assertEqual(self.posts, [])

    def test_error_does_not_echo_server_body_or_password(self):
        self.reject_post = True
        with self.assertRaises(CredentialsError) as error:
            load_into_smd(self.path, self.origin)
        self.assertNotIn(SECRET, str(error.exception))

    def test_unconfigured_is_optional_and_password_characters_are_preserved(self):
        self.path.unlink()
        self.assertFalse(load_into_smd(self.path, self.origin)[0])
        self.path.write_text("SMD_SSH_USERNAME=root\nSMD_SSH_PASSWORD= a=b#c ")
        self.path.chmod(0o600)
        self.assertEqual(read_local_credentials(self.path).password, " a=b#c ")

    @unittest.skipIf(os.name == "nt", "POSIX file permission check")
    def test_broad_file_permissions_rejected(self):
        self.path.chmod(0o644)
        with self.assertRaises(CredentialsError):
            read_local_credentials(self.path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
