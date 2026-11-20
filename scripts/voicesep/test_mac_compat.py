import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "mac_winsound", Path(__file__).parent / "mac_compat/winsound.py"
)
player = importlib.util.module_from_spec(spec)
spec.loader.exec_module(player)


class CalibrationCompatibilityTests(unittest.TestCase):
    def test_original_memory_call_plays_exact_data_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = []

            def fake_run(command, **kwargs):
                self.assertEqual(command[0], "/usr/bin/afplay")
                self.assertEqual(Path(command[1]).read_bytes(), b"RIFF-test")
                self.assertEqual(kwargs["timeout"], 8)
                paths.append(Path(command[1]))

            with (
                patch.object(player.sys, "platform", "darwin"),
                patch.dict(player.os.environ, {"VOICESEP_COMPAT_TMP": directory}),
                patch.object(player.subprocess, "run", side_effect=fake_run),
            ):
                player.PlaySound(b"RIFF-test", player.SND_MEMORY)
            self.assertFalse(paths[0].exists())

    def test_playback_failure_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(player.sys, "platform", "darwin"),
                patch.dict(player.os.environ, {"VOICESEP_COMPAT_TMP": directory}),
                patch.object(
                    player.subprocess,
                    "run",
                    side_effect=subprocess.CalledProcessError(1, "afplay"),
                ),
                self.assertRaises(subprocess.CalledProcessError),
            ):
                player.PlaySound(b"RIFF-test", player.SND_MEMORY)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_other_playback_modes_are_rejected(self):
        with self.assertRaises(ValueError):
            player.PlaySound("filename.wav", 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
