#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$TEST_DIR/.." && pwd)"
source "$PROJECT_DIR/tools/build-env.sh"
init_build_environment
TEST_BUILD_DIR="${TEST_BUILD_DIR:-$PROJECT_DIR/build/runtime-tests}"
TEST_APK_FILE="${TEST_APK_FILE:-$PROJECT_DIR/build/runtime-tests.apk}"
mkdir -p "$TEST_BUILD_DIR" "$(dirname "$TEST_APK_FILE")"
TEST_BUILD_DIR="$(cd "$TEST_BUILD_DIR" && pwd)"
prepare_signing
TASK_BUILD_DIR="$(mktemp -d "$TEST_BUILD_DIR/compile.XXXXXX")"
trap 'rm -rf "$TASK_BUILD_DIR"' EXIT
mkdir -p "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/dex"
"$BUILD_TOOLS_DIR/aapt2" link -o "$TASK_BUILD_DIR/resources.apk" --manifest "$TEST_DIR/AndroidManifest.xml" -I "$ANDROID_JAR"
python3 - "$TEST_DIR" "$TASK_BUILD_DIR/sources.txt" <<'PY'
from pathlib import Path
import sys
sources = sorted(p for p in Path(sys.argv[1]).glob('*.java') if p.name != 'LedgerCoreTest.java')
Path(sys.argv[2]).write_text('\n'.join('"' + str(p).replace('\\', '\\\\').replace('"', '\\"') + '"' for p in sources), encoding='utf-8')
PY
"$JAVAC_BIN" --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$TASK_BUILD_DIR/classes" "@$TASK_BUILD_DIR/sources.txt"
"$JAR_BIN" cf "$TASK_BUILD_DIR/classes.jar" -C "$TASK_BUILD_DIR/classes" .
"$JAVA_BIN" -cp "$BUILD_TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/classes.jar"
cp "$TASK_BUILD_DIR/resources.apk" "$TASK_BUILD_DIR/unsigned.apk"
(cd "$TASK_BUILD_DIR/dex" && zip -q -u "$TASK_BUILD_DIR/unsigned.apk" classes*.dex)
"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$TASK_BUILD_DIR/unsigned.apk" "$TASK_BUILD_DIR/aligned.apk"
sign_apk "$TASK_BUILD_DIR/aligned.apk" "$TEST_APK_FILE"
echo "Test APK ($BUILD_TYPE signature): $TEST_APK_FILE"
