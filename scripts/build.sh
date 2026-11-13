#!/usr/bin/env bash
# Build everything and run every suite the environment allows.
#
# Env vars (all optional; suites that need one skip without it):
#   SGMO_APK          a real Skullgirls 8.12.0 copy (xapk/apks/dir) for real-binary tests
#   SGMO_CONTENT      a folder from tools/content-gen/extract_content.py, for the server suite
#   SGMO_BUILD_TOOLS  an Android build-tools folder, for an independent apksigner verify
#
# Exit non-zero if any step fails.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
fail=0
step() { echo; echo "=== $* ==="; }

PY="${PYTHON:-python3}"

step "Java helper (dexlib2 + apksig)"
if command -v javac >/dev/null 2>&1; then
    bash patcher/java/build.sh >/dev/null
    JAR="$(bash patcher/java/build.sh 2>/dev/null | tail -1)"
    CP="$JAR:$(ls "${SGMO_CACHE:-$HOME/.cache/sgm-offline}"/deps/*.jar | tr '\n' ':')"
    java -cp "$CP" sgmo.helper.Main selftest
else
    echo "no javac on PATH — skipping the Java helper and its self-test"
fi

step "patcher unit + real-binary tests"
( cd patcher && "$PY" -m unittest discover -s tests -v ) || fail=1

step "reference server end-to-end tests (Python)"
( cd reference-server && "$PY" -m unittest discover -s tests -v ) || fail=1

if [ -n "${SGMO_SERVER_CMD:-}" ]; then
    step "reference-server suite against SGMO_SERVER_CMD"
    ( cd reference-server && SGMO_SERVER_CMD="$SGMO_SERVER_CMD" "$PY" -m unittest discover -s tests -v ) || fail=1
fi

echo
if [ "$fail" -eq 0 ]; then echo "BUILD OK"; else echo "BUILD FAILED"; exit 1; fi
