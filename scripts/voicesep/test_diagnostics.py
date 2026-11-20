import unittest

from diagnostics import redact, report


class DiagnosticTests(unittest.TestCase):
    def test_ssh_failure_without_device_logs_is_explicit(self):
        text = report(
            {
                "state": "error",
                "message": "Unable to connect to port 22",
                "clock_calibration": None,
            },
            [],
            "now",
        )
        self.assertIn("操作失败", text)
        self.assertIn("port 22", text)
        self.assertIn("尚未连上 SSH", text)
        self.assertIn("不能判为校准通过", text)

    def test_accepted_request_is_not_mistaken_for_completion(self):
        text = report({"state": "calibrating", "operation_busy": True}, [], "now")
        self.assertIn("HTTP 200 仅表示请求已接受", text)

    def test_old_profile_is_not_claimed_as_successful_new_attempt(self):
        text = report(
            {"state": "error", "clock_calibration": {"actual_sample_rate": 24000}},
            [],
            "now",
        )
        self.assertIn("已有记录不等于本次操作成功", text)

    def test_credentials_and_websocket_tokens_are_hidden(self):
        value = redact(
            'pw=test-secret /ws?token=private-session&other=1 "api=test-api"',
            ["test-secret", "test-api"],
        )
        self.assertNotIn("test-secret", value)
        self.assertNotIn("test-api", value)
        self.assertNotIn("private-session", value)
        self.assertIn("other=1", value)


if __name__ == "__main__":
    unittest.main(verbosity=2)
