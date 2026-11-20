"""Small macOS dependency shim for VoiceSep's unchanged calibration call.

Only the dedicated SMD child receives this directory on PYTHONPATH. This is not
installed globally and is never used on Windows. Playback requires the original
SMD user action; merely importing this module produces no sound.
"""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

SND_MEMORY = 0x0004


def PlaySound(sound, flags):  # Match the existing Windows API used by VoiceSep.
    if sys.platform != "darwin" or flags != SND_MEMORY or not isinstance(sound, bytes):
        raise ValueError(
            "This local shim only supports synchronous in-memory WAV calibration on macOS"
        )
    if not sound.startswith(b"RIFF") or len(sound) > 4 * 1024 * 1024:
        raise ValueError("Expected a bounded RIFF WAV calibration tone")
    root = Path(
        os.environ.get("VOICESEP_COMPAT_TMP")
        or Path(__file__).resolve().parents[3] / "artifacts/voicesep-stack/clock-audio"
    )
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tone-", dir=root) as temporary:
        filename = Path(temporary) / "calibration.wav"
        filename.write_bytes(sound)
        subprocess.run(
            ["/usr/bin/afplay", str(filename)],
            check=True,
            timeout=8,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
