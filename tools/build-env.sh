#!/usr/bin/env bash
# Shared toolchain/signing helpers. Source this after setting PROJECT_DIR.
init_build_environment() {
    local legacy_root sdk_root
    legacy_root="$(cd "$PROJECT_DIR/../.." && pwd)/work/toolchain"
    TASK_JAVA_HOME="${JAVA_HOME:-${TASK_JAVA_HOME:-}}"
    if [ -z "$TASK_JAVA_HOME" ] && [ -x "$legacy_root/java/Contents/Home/bin/javac" ]; then
        TASK_JAVA_HOME="$legacy_root/java/Contents/Home"
    fi
    if [ -n "$TASK_JAVA_HOME" ]; then
        JAVA_BIN="$TASK_JAVA_HOME/bin/java"; JAVAC_BIN="$TASK_JAVA_HOME/bin/javac"
        JAR_BIN="$TASK_JAVA_HOME/bin/jar"; KEYTOOL_BIN="$TASK_JAVA_HOME/bin/keytool"
    else
        JAVA_BIN="$(command -v java || true)"; JAVAC_BIN="$(command -v javac || true)"
        JAR_BIN="$(command -v jar || true)"; KEYTOOL_BIN="$(command -v keytool || true)"
    fi
    for tool in "$JAVA_BIN" "$JAVAC_BIN" "$JAR_BIN" "$KEYTOOL_BIN"; do
        [ -n "$tool" ] && [ -x "$tool" ] || { echo 'JDK 17 is required; set JAVA_HOME.' >&2; return 1; }
    done
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [ -n "$sdk_root" ]; then
        BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$sdk_root/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}}"
        ANDROID_JAR="${ANDROID_JAR:-$sdk_root/platforms/android-${ANDROID_API:-35}/android.jar}"
    else
        BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$legacy_root/build-tools/android-15}"
        ANDROID_JAR="${ANDROID_JAR:-$legacy_root/platform/android-35/android.jar}"
    fi
    [ -x "$BUILD_TOOLS_DIR/aapt2" ] && [ -f "$ANDROID_JAR" ] || {
        echo 'Android SDK not found. Set ANDROID_SDK_ROOT (Build Tools 35.0.0 and Platform 35).' >&2; return 1;
    }
    for command_name in python3 zip; do command -v "$command_name" >/dev/null || { echo "Missing: $command_name" >&2; return 1; }; done
    BUILD_TYPE="${BUILD_TYPE:-debug}"
    case "$BUILD_TYPE" in debug|release) ;; *) echo 'BUILD_TYPE must be debug or release.' >&2; return 1;; esac
}

prepare_signing() {
    if [ "$BUILD_TYPE" = release ]; then
        [ -n "${SIGNING_DIR:-}" ] || { echo 'Release requires an explicit SIGNING_DIR containing the existing private release key.' >&2; return 1; }
        SIGNING_KEYSTORE="${SIGNING_KEYSTORE:-$SIGNING_DIR/shunlu-release.p12}"
        SIGNING_PASSWORD_FILE="${SIGNING_PASSWORD_FILE:-$SIGNING_DIR/password.txt}"
        SIGNING_ALIAS="${SIGNING_ALIAS:-shunlu}"
        [ -s "$SIGNING_KEYSTORE" ] && [ -s "$SIGNING_PASSWORD_FILE" ] || {
            echo 'Existing release key/password file missing. No release key will be generated.' >&2; return 1;
        }
    else
        # Conventional debug-only credentials; never used to sign release updates.
        local debug_dir="${DEBUG_SIGNING_DIR:-$PROJECT_DIR/build/debug-signing}"
        mkdir -p "$debug_dir"; chmod 700 "$debug_dir"
        SIGNING_KEYSTORE="$debug_dir/debug.p12"; SIGNING_PASSWORD_FILE="$debug_dir/password.txt"; SIGNING_ALIAS=androiddebugkey
        if [ ! -f "$SIGNING_KEYSTORE" ]; then
            "$KEYTOOL_BIN" -genkeypair -keystore "$SIGNING_KEYSTORE" -storetype PKCS12 \
                -storepass android -keypass android -alias "$SIGNING_ALIAS" -keyalg RSA -keysize 2048 \
                -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
        fi
        printf '%s\n' android > "$SIGNING_PASSWORD_FILE"
        chmod 600 "$SIGNING_KEYSTORE" "$SIGNING_PASSWORD_FILE"
    fi
}

sign_apk() {
    "$JAVA_BIN" -jar "$BUILD_TOOLS_DIR/lib/apksigner.jar" sign \
        --ks "$SIGNING_KEYSTORE" --ks-key-alias "$SIGNING_ALIAS" \
        --ks-pass "file:$SIGNING_PASSWORD_FILE" --v4-signing-enabled false --out "$2" "$1"
    "$JAVA_BIN" -jar "$BUILD_TOOLS_DIR/lib/apksigner.jar" verify --verbose "$2"
    "$BUILD_TOOLS_DIR/zipalign" -c -p 4 "$2"
}
