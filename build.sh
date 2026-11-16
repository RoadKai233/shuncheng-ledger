#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$PROJECT_DIR/tools/build-env.sh"
init_build_environment
BUILD_DIR="${BUILD_DIR:-$PROJECT_DIR/build}"
APK_FILE="${APK_FILE:-$BUILD_DIR/shuncheng-ledger-$BUILD_TYPE.apk}"
mkdir -p "$BUILD_DIR" "$(dirname "$APK_FILE")"
BUILD_DIR="$(cd "$BUILD_DIR" && pwd)"
prepare_signing
TASK_BUILD_DIR="$(mktemp -d "$BUILD_DIR/compile.XXXXXX")"
trap 'rm -rf "$TASK_BUILD_DIR"' EXIT
mkdir -p "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/generated" "$TASK_BUILD_DIR/dex"

# Keep the checked-in manifest untouched; debug builds are visibly labelled.
python3 - "$PROJECT_DIR/AndroidManifest.xml" "$TASK_BUILD_DIR/AndroidManifest.xml" "$BUILD_TYPE" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
root = ET.parse(sys.argv[1]); app = root.getroot().find('application')
if sys.argv[3] == 'debug':
    key = '{http://schemas.android.com/apk/res/android}'
    app.set(key + 'debuggable', 'true')
    app.set(key + 'label', app.get(key + 'label', '顺程记') + '（调试版）')
root.write(sys.argv[2], encoding='utf-8', xml_declaration=True)
PY
"$BUILD_TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$TASK_BUILD_DIR/resources.zip"
"$BUILD_TOOLS_DIR/aapt2" link -o "$TASK_BUILD_DIR/resources.apk" \
    --manifest "$TASK_BUILD_DIR/AndroidManifest.xml" -I "$ANDROID_JAR" \
    --java "$TASK_BUILD_DIR/generated" "$TASK_BUILD_DIR/resources.zip"
python3 - "$PROJECT_DIR/src" "$TASK_BUILD_DIR/generated" "$TASK_BUILD_DIR/sources.txt" <<'PY'
from pathlib import Path
import sys
sources = sorted(Path(sys.argv[1]).rglob('*.java')) + sorted(Path(sys.argv[2]).rglob('*.java'))
Path(sys.argv[3]).write_text('\n'.join('"' + str(p).replace('\\', '\\\\').replace('"', '\\"') + '"' for p in sources), encoding='utf-8')
PY
"$JAVAC_BIN" --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$TASK_BUILD_DIR/classes" "@$TASK_BUILD_DIR/sources.txt"
"$JAR_BIN" cf "$TASK_BUILD_DIR/classes.jar" -C "$TASK_BUILD_DIR/classes" .
"$JAVA_BIN" -cp "$BUILD_TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 26 --lib "$ANDROID_JAR" --output "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/classes.jar"
cp "$TASK_BUILD_DIR/resources.apk" "$TASK_BUILD_DIR/unsigned.apk"
(cd "$TASK_BUILD_DIR/dex" && zip -q -u "$TASK_BUILD_DIR/unsigned.apk" classes*.dex)
"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$TASK_BUILD_DIR/unsigned.apk" "$TASK_BUILD_DIR/aligned.apk"
sign_apk "$TASK_BUILD_DIR/aligned.apk" "$APK_FILE"
echo "Built $BUILD_TYPE APK: $APK_FILE"
if [ "$BUILD_TYPE" = debug ]; then echo 'DEBUG signature: for isolated testing; cannot replace the official release installation.'; fi
