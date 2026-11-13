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

if command -v cargo >/dev/null 2>&1; then
    step "Rust server core: build + unit tests"
    ( cd server && cargo build --release -p sgm-standalone && cargo test -p sgm-core ) || fail=1
    if [ -n "${SGMO_CONTENT:-}" ]; then
        step "Rust server parity: login+startup against the Python conformance suite"
        BIN="$ROOT/server/target/release/sgm-standalone"
        ( cd reference-server && SGMO_CONTENT="$SGMO_CONTENT" \
            SGMO_SERVER_CMD="$BIN --content {content} --db {data} --host 127.0.0.1 --port {port}" \
            "$PY" -m unittest \
              tests.test_flow.FlowTest.test_login_creates_and_resumes_user \
              tests.test_flow.FlowTest.test_startup_calls \
              tests.test_flow.FlowTest.test_requires_session \
              tests.test_flow.FlowTest.test_unknown_endpoint_is_harmless \
              tests.test_flow.FlowTest.test_signature_known_vector ) || fail=1
    fi
fi

echo
if [ "$fail" -eq 0 ]; then echo "BUILD OK"; else echo "BUILD FAILED"; exit 1; fi
