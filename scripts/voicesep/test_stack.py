"""Process identity tests never stop or start a real service."""

import io
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import stack


class StackTests(unittest.TestCase):
    def test_start_time_is_a_fixed_utc_epoch(self):
        with patch.object(
            stack, "process_field", return_value="Fri Nov 20 09:17:53 2026"
        ):
            self.assertEqual(stack.started_epoch(123), 1795166273)

    def legacy(self, **updates):
        entry = {
            "name": "smd",
            "pid": 123,
            "created": "Fri Nov 20 17:17:53 2026",
            "marker": "/repo/VoiceSep/research/SMD",
            "port": 8012,
        }
        return {**entry, **updates}

    def test_ps_identity_ignores_chinese_locale_and_user_timezone(self):
        original = {
            "LC_ALL": "zh_CN.UTF-8",
            "LANG": "zh_CN.UTF-8",
            "TZ": "UTC",
            "COLUMNS": "40",
        }
        long_command = (
            "/long/python/"
            + "x" * 140
            + " --app-dir /repo/VoiceSep/research/SMD --port 8012"
        )

        def ps(args, **kwargs):
            self.assertIn("-ww", args)
            self.assertEqual(kwargs["env"]["LC_ALL"], "C")
            if args[-1] == "command=":
                return SimpleNamespace(stdout=long_command)
            value = (
                "Fri Nov 20 09:17:53 2026"
                if kwargs["env"].get("TZ") == "UTC"
                else "Fri Nov 20 17:17:53 2026"
            )
            return SimpleNamespace(stdout=value)

        with (
            patch.dict(os.environ, original),
            patch.object(stack.subprocess, "run", side_effect=ps),
        ):
            self.assertTrue(stack.ours(self.legacy()))
            self.assertIsInstance(stack.started_epoch(123), int)
            self.assertEqual(os.environ["LC_ALL"], original["LC_ALL"])
            self.assertEqual(os.environ["TZ"], "UTC")

    def test_legacy_chinese_date_is_recognized_without_changing_locale(self):
        with (
            patch.object(stack, "created", return_value="Fri Nov 20 17:17:53 2026"),
            patch.object(
                stack, "command", return_value="python /repo/VoiceSep/research/SMD"
            ),
        ):
            self.assertTrue(stack.ours(self.legacy(created="五 11月/20 17:17:53 2026")))
            self.assertFalse(stack.ours(self.legacy(created="invalid")))

    def test_pid_reuse_and_command_changes_are_rejected(self):
        with (
            patch.object(stack, "created", return_value="Fri Nov 20 18:00:00 2026"),
            patch.object(
                stack, "command", return_value="python /repo/VoiceSep/research/SMD"
            ),
        ):
            self.assertFalse(stack.ours(self.legacy()))
        with (
            patch.object(stack, "started_epoch", return_value=100),
            patch.object(stack, "command", return_value="other service"),
        ):
            self.assertFalse(stack.ours(self.legacy(started_epoch_utc=100)))
        with (
            patch.object(stack, "started_epoch", return_value=101),
            patch.object(
                stack, "command", return_value="python /repo/VoiceSep/research/SMD"
            ),
        ):
            self.assertFalse(stack.ours(self.legacy(started_epoch_utc=100)))

    def test_numeric_identity_is_independent_of_legacy_date_text(self):
        with (
            patch.object(stack, "started_epoch", return_value=100),
            patch.object(
                stack, "command", return_value="python /repo/VoiceSep/research/SMD"
            ),
        ):
            self.assertTrue(
                stack.ours(self.legacy(started_epoch_utc=100, created="任意旧显示文字"))
            )
            self.assertFalse(stack.ours(self.legacy(started_epoch_utc=None)))

    def test_invalid_pid_or_empty_marker_cannot_authorize_stop(self):
        with patch.object(stack, "process_field") as ps:
            self.assertFalse(stack.ours(self.legacy(pid=0)))
            self.assertFalse(stack.ours(self.legacy(pid=True)))
            self.assertFalse(stack.ours(self.legacy(marker="")))
            ps.assert_not_called()

    def test_legacy_migration_requires_a_verified_existing_identity(self):
        entries = [self.legacy(), self.legacy(pid=456)]
        with (
            patch.object(stack, "ours", side_effect=lambda item: item["pid"] == 123),
            patch.object(stack, "started_epoch", return_value=100),
            patch.object(stack, "save_state") as save,
        ):
            stack.upgrade_identities(entries)
            result = save.call_args.args[0]
            self.assertEqual(result[0]["started_epoch_utc"], 100)
            self.assertNotIn("started_epoch_utc", result[1])
            self.assertNotIn("started_epoch_utc", entries[0])

    def test_start_reuses_running_services_without_spawning_or_killing(self):
        entries = [
            self.legacy(name=name, port=port) for name, port in stack.SERVICES.items()
        ]
        with (
            patch.object(stack, "read_state", return_value=entries),
            patch.object(stack, "ours", return_value=True),
            patch.object(stack, "upgrade_identities") as upgrade,
            patch.object(stack, "load_credentials") as credentials,
            patch.object(stack, "status"),
            patch.object(stack.subprocess, "Popen") as spawn,
            patch.object(stack.os, "kill") as kill,
        ):
            stack.start(Path("/unused"))
            upgrade.assert_called_once_with(entries)
            credentials.assert_called_once()
            spawn.assert_not_called()
            kill.assert_not_called()

    def test_unknown_occupied_port_lists_pid_without_stopping_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            python = root / ".venv/bin/python"
            python.parent.mkdir(parents=True)
            python.touch()
            with (
                patch.object(stack, "read_state", return_value=[]),
                patch.object(stack, "occupied", side_effect=lambda port: port == 8012),
                patch.object(stack, "listening_pids", return_value=[999]),
                patch.object(stack.subprocess, "Popen") as spawn,
                patch.object(stack.os, "kill") as kill,
            ):
                with self.assertRaises(RuntimeError) as error:
                    stack.start(root)
                self.assertIn("8012", str(error.exception))
                self.assertIn("999", str(error.exception))
                spawn.assert_not_called()
                kill.assert_not_called()

    def test_status_distinguishes_unverified_listener_from_stopped(self):
        with (
            patch.object(stack, "read_state", return_value=[self.legacy()]),
            patch.object(stack, "ours", return_value=False),
            patch.object(stack, "occupied", side_effect=lambda port: port == 8012),
            patch.object(stack, "listening_pids", return_value=[999]),
            patch.object(stack, "print_endpoints"),
            patch("sys.stdout", new_callable=io.StringIO) as output,
        ):
            stack.status()
            self.assertIn("smd: listening-unverified", output.getvalue())
            self.assertIn("999", output.getvalue())
            self.assertIn("direction: stopped", output.getvalue())
            self.assertNotIn("smd: stopped", output.getvalue())

    def test_stop_never_signals_an_unverified_process(self):
        with (
            patch.object(stack, "read_state", return_value=[self.legacy()]),
            patch.object(stack, "ours", return_value=False),
            patch.object(stack.os, "kill") as kill,
            patch.object(stack, "status"),
        ):
            stack.stop()
            kill.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
