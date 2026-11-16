#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${JAVA_HOME:-${TASK_JAVA_HOME:-}}" ]; then
    TEST_JAVA_HOME="${JAVA_HOME:-$TASK_JAVA_HOME}"
    JAVAC_BIN="$TEST_JAVA_HOME/bin/javac"; JAVA_BIN="$TEST_JAVA_HOME/bin/java"
else
    JAVAC_BIN="$(command -v javac)"; JAVA_BIN="$(command -v java)"
fi
TEST_BUILD_DIR="${TEST_BUILD_DIR:-$PROJECT_DIR/build/core-tests}"
mkdir -p "$TEST_BUILD_DIR"
"$JAVAC_BIN" --release 8 -encoding UTF-8 -d "$TEST_BUILD_DIR" \
    "$PROJECT_DIR/src/cn/personal/shunfeng/LedgerRules.java" \
    "$PROJECT_DIR/src/cn/personal/shunfeng/BackupCrypto.java" "$PROJECT_DIR/tests/LedgerCoreTest.java"
"$JAVA_BIN" -cp "$TEST_BUILD_DIR" LedgerCoreTest
