#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_output="$(mktemp -d "${TMPDIR:-/tmp}/acoustics-test.XXXXXX")"
trap 'rm -rf "$task_output"' EXIT
task_source="$task_root/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/acoustics/core"
javac -encoding UTF-8 -d "$task_output" "$task_source"/*.java "$task_root/tests/AcousticControllerTest.java"
java -cp "$task_output" AcousticControllerTest
