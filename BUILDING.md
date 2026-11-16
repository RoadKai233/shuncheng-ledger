# 构建顺程记

项目为无第三方运行依赖的原生 Android Java 应用。最低 API 26，编译使用 Android SDK Platform 35 与 Build Tools 35.0.0。无需 Gradle。

## 环境

准备 JDK 17、Python 3、zip 与 Android SDK。Linux、macOS 或 Windows 的 WSL 均可使用 Bash 脚本；SDK 必须与实际运行脚本的操作系统匹配。

```sh
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0"
```

SDK 许可按官方工具提示处理。`ANDROID_HOME` 也受支持；需要自定义路径时可设置 `BUILD_TOOLS_DIR`、`ANDROID_JAR`。本项目原有工作区布局仍提供工具链回退，不需要把该布局复制进公开仓库。

## 调试构建（默认）

```sh
bash tests/run-core-tests.sh
bash build.sh
bash tests/build-runtime-tests.sh
bash tests/build-update-tests.sh
```

输出：

- `build/shuncheng-ledger-debug.apk`：标记为「调试版」的应用。
- `build/runtime-tests.apk`：单独的 Instrumentation 测试包。
- `build/update-tests.apk`：更新元数据、校验和安装包验证的独立测试包，内含纯合成 APK 样本；样本不能安装为应用。

脚本在被 Git 忽略的 `build/debug-signing` 中生成本地调试证书。主 APK 和测试 APK 共用该证书，默认调试密码为 Android 惯用的公开测试值；这不是正式签名。

调试包与正式包包名相同，但签名不同，不能覆盖正式安装。请使用隔离模拟器测试，不要为安装调试包而卸载正在使用的账本。

可配置输出与临时构建目录：

```sh
BUILD_DIR="build/custom" APK_FILE="build/custom/demo-debug.apk" bash build.sh
TEST_BUILD_DIR="build/test-work" TEST_APK_FILE="build/test.apk" bash tests/build-runtime-tests.sh
```

`DEBUG_SIGNING_DIR` 可指定共用的调试签名目录；主 APK 和测试 APK 必须使用同一签名。

## 正式构建

正式构建必须明确设置 `BUILD_TYPE=release` 和存放原发布密钥的 `SIGNING_DIR`。仓库和 GitHub Actions 都不含正式密钥，也不会自动创建新的正式密钥。

```sh
BUILD_TYPE=release \
SIGNING_DIR="/private/path/to/existing-signing" \
APK_FILE="build/shuncheng-ledger-release.apk" \
bash build.sh
```

默认签名文件名为 `shunlu-release.p12`、密码文件为 `password.txt`，别名为 `shunlu`，为旧安装的兼容性保留。需要其它文件名时，设置 `SIGNING_KEYSTORE`、`SIGNING_PASSWORD_FILE`、`SIGNING_ALIAS`。这些路径和文件均应保留在仓库之外，不要上传至 GitHub。

更新必须使用原签名并提高 Manifest 的 `versionCode`。不要通过卸载旧版来更新。面向旧版的正式安装包由维护者本地签名，再手动上传 Release；公开 CI 只产生调试包。

若需要在隔离模拟器上测试正式候选，测试 APK 也必须显式使用相同签名：

```sh
BUILD_TYPE=release SIGNING_DIR="/private/path/to/existing-signing" \
  bash tests/build-runtime-tests.sh
```

## 运行测试

纯 Java 核心测试无需 Android 设备。GitHub Actions 会运行这些测试，并编译主调试 APK 和 Instrumentation APK。

Instrumentation 会创建/清理虚构账目、切换模拟器外观、操作锁屏状态，只能用于隔离测试模拟器，不能连接日常使用的手机。运行前阅读 `tests/RuntimeChecks.java` 的设备状态前提并配置测试锁屏凭据。

```sh
adb -s emulator-5554 install -r build/shuncheng-ledger-debug.apk
adb -s emulator-5554 install -r build/runtime-tests.apk
adb -s emulator-5554 shell am instrument -w cn.personal.shunfeng.tests/.RuntimeChecks
```

公开 CI 不配置正式签名 secret，也不会自动发布正式 APK。发布流程及 `update.json` 说明见 [RELEASING.md](RELEASING.md)。

官方参考：[命令行构建](https://developer.android.com/build/building-cmdline)、[APK 签名](https://developer.android.com/tools/apksigner)。
