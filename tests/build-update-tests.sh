#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$TEST_DIR/.." && pwd)"
source "$PROJECT_DIR/tools/build-env.sh"
init_build_environment
TEST_BUILD_DIR="${TEST_BUILD_DIR:-$PROJECT_DIR/build/update-tests}"
TEST_APK_FILE="${TEST_APK_FILE:-$PROJECT_DIR/build/update-tests.apk}"
mkdir -p "$TEST_BUILD_DIR" "$(dirname "$TEST_APK_FILE")"
TEST_BUILD_DIR="$(cd "$TEST_BUILD_DIR" && pwd)"
prepare_signing
TASK_BUILD_DIR="$(mktemp -d "$TEST_BUILD_DIR/compile.XXXXXX")"
trap 'rm -rf "$TASK_BUILD_DIR"' EXIT
mkdir -p "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/assets/update-fixtures" "$TASK_BUILD_DIR/manifests"
# Fixtures contain no app code and must never be installed. No real user data or credential is embedded.
python3 - "$PROJECT_DIR/AndroidManifest.xml" "$TASK_BUILD_DIR/manifests" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
ns='{http://schemas.android.com/apk/res/android}'; ET.register_namespace('android',ns[1:-1])
app=ET.parse(sys.argv[1]).getroot(); current=int(app.get(ns+'versionCode')); package=app.get('package')
fixtures=[('valid',package,current+1,26),('wrong-package',package+'.fixture',current+1,26),('wrong-signature',package,current+1,26),('same-version',package,current,26),('downgrade',package,max(1,current-1),26),('unsupported-sdk',package,current+1,99)]
for name,pkg,code,sdk in fixtures:
 root=ET.Element('manifest',{'package':pkg,ns+'versionCode':str(code),ns+'versionName':'fixture.'+str(code)})
 ET.SubElement(root,'uses-sdk',{ns+'minSdkVersion':str(sdk),ns+'targetSdkVersion':str(max(35,sdk))})
 ET.SubElement(root,'application',{ns+'label':'Synthetic updater fixture',ns+'hasCode':'false',ns+'allowBackup':'false'})
 ET.ElementTree(root).write(Path(sys.argv[2])/f'{name}.xml',encoding='utf-8',xml_declaration=True)
PY
# Generate a temporary second signer to exercise signature mismatch. It is deleted with intermediates.
python3 - "$KEYTOOL_BIN" "$TASK_BUILD_DIR" <<'PY'
from pathlib import Path
import os,secrets,subprocess,sys
folder=Path(sys.argv[2]); password=secrets.token_urlsafe(32)
(folder/'fixture-password.txt').write_text(password+'\n',encoding='ascii'); (folder/'fixture-password.txt').chmod(0o600)
env=dict(os.environ,UPDATE_FIXTURE_PASSWORD=password)
subprocess.run([sys.argv[1],'-genkeypair','-keystore',str(folder/'fixture-signing.p12'),'-storetype','PKCS12','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','365','-storepass:env','UPDATE_FIXTURE_PASSWORD','-keypass:env','UPDATE_FIXTURE_PASSWORD','-dname','CN=Synthetic Test Fixture'],env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
PY
for fixture_name in valid wrong-package wrong-signature same-version downgrade unsupported-sdk; do
    "$BUILD_TOOLS_DIR/aapt2" link -o "$TASK_BUILD_DIR/$fixture_name-unsigned.apk" --manifest "$TASK_BUILD_DIR/manifests/$fixture_name.xml" -I "$ANDROID_JAR"
    "$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$TASK_BUILD_DIR/$fixture_name-unsigned.apk" "$TASK_BUILD_DIR/$fixture_name-aligned.apk"
    if [ "$fixture_name" = wrong-signature ]; then
        "$JAVA_BIN" -jar "$BUILD_TOOLS_DIR/lib/apksigner.jar" sign --ks "$TASK_BUILD_DIR/fixture-signing.p12" --ks-key-alias fixture --ks-pass "file:$TASK_BUILD_DIR/fixture-password.txt" --v4-signing-enabled false --out "$TASK_BUILD_DIR/assets/update-fixtures/$fixture_name.apk" "$TASK_BUILD_DIR/$fixture_name-aligned.apk"
    else
        sign_apk "$TASK_BUILD_DIR/$fixture_name-aligned.apk" "$TASK_BUILD_DIR/assets/update-fixtures/$fixture_name.apk" >/dev/null
    fi
done
python3 - "$TASK_BUILD_DIR/manifests" "$TASK_BUILD_DIR/assets/update-fixtures" <<'PY'
from pathlib import Path
import hashlib,json,sys,xml.etree.ElementTree as ET
folder=Path(sys.argv[2]); entries=[]; ns='{http://schemas.android.com/apk/res/android}'
for apk in sorted(folder.glob('*.apk')):
 root=ET.parse(Path(sys.argv[1])/(apk.stem+'.xml')).getroot(); data=apk.read_bytes()
 entries.append(dict(file=apk.name,versionCode=int(root.get(ns+'versionCode')),versionName=root.get(ns+'versionName'),size=len(data),sha256=hashlib.sha256(data).hexdigest()))
(folder/'fixtures.json').write_text(json.dumps(dict(fixtures=entries)),encoding='utf-8')
PY
"$BUILD_TOOLS_DIR/aapt2" link -o "$TASK_BUILD_DIR/resources.apk" --manifest "$TEST_DIR/UpdateManifest.xml" -I "$ANDROID_JAR" -A "$TASK_BUILD_DIR/assets"
"$JAVAC_BIN" --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$TASK_BUILD_DIR/classes" "$TEST_DIR/UpdateChecks.java"
"$JAR_BIN" cf "$TASK_BUILD_DIR/classes.jar" -C "$TASK_BUILD_DIR/classes" .
"$JAVA_BIN" -cp "$BUILD_TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/classes.jar"
cp "$TASK_BUILD_DIR/resources.apk" "$TASK_BUILD_DIR/unsigned.apk"
(cd "$TASK_BUILD_DIR/dex" && zip -q -u "$TASK_BUILD_DIR/unsigned.apk" classes*.dex)
"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$TASK_BUILD_DIR/unsigned.apk" "$TASK_BUILD_DIR/aligned.apk"
sign_apk "$TASK_BUILD_DIR/aligned.apk" "$TEST_APK_FILE"
echo "Updater test APK ($BUILD_TYPE signature): $TEST_APK_FILE"
