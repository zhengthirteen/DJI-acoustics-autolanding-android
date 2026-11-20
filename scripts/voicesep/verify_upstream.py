#!/usr/bin/env python3
"""Run unmodified VoiceSep SMD tests with a Mac-safe test-only os facade."""

import argparse
import os
import subprocess
import sys
import unittest
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--voicesep-root", type=Path, default=PROJECT.parent / "VoiceSep"
    )
    args = parser.parse_args()
    root = args.voicesep_root.resolve()
    if Path(sys.prefix).resolve() != (root / ".venv").resolve():
        raise SystemExit(
            subprocess.call(
                [
                    str(root / ".venv/bin/python"),
                    "-B",
                    str(Path(__file__).resolve()),
                    *sys.argv[1:],
                ]
            )
        )
    sys.path.insert(0, str(root / "research/SMD"))
    import smd.service

    class LocalOs:
        # Upstream tests simulate Windows by patching smd.service.os.name. Patching
        # the shared os module would break pathlib on Mac; keep that test local.
        name = os.name

        def __getattr__(self, name):
            return getattr(os, name)

    previous = smd.service.os
    if sys.platform == "darwin":
        smd.service.os = LocalOs()
    try:
        suite = unittest.defaultTestLoader.discover(str(root / "research/SMD/tests"))
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        smd.service.os = previous
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
