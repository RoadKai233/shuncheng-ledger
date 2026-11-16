# 更新测试

只在隔离的 Android 模拟器运行。测试 APK 必须与被测 App 使用同一签名；公开构建默认 debug，发布签名测试需显式配置 `BUILD_TYPE=release` 和自己的 `SIGNING_DIR`。没有内置锁屏 PIN。

## 离线模块

```sh
bash tests/build-update-tests.sh
adb -s "$ANDROID_SERIAL" install -r build/update-tests.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w cn.personal.shunfeng.updatetests/cn.personal.shunfeng.tests.UpdateChecks
```

包含严格 JSON / URL / CDN 白名单、大小与 SHA-256、包名 / 版本 / 签名 / minSdk、持久化缓存完整复验、只读 Provider 路径与 URI 权限。合成 APK 仅用于验证，不可安装。测试会暂存并还原原更新缓存。

发布真实 release 后，可执行只读在线检查（不下载、不安装）：

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -e live_check true cn.personal.shunfeng.updatetests/cn.personal.shunfeng.tests.UpdateChecks
```

## Android 安装确认与进程恢复

需额外制作与被测 App 同包名、同签名且 versionCode 更高的真实 APK，命名 `e2e-update-v8.apk` 放入测试 App 的私有 `files` 目录（AOSP 可用 adb root 准备，确保文件属于目标应用 UID 并 restorecon）。该候选仅放本地测试目录，不能当公开 release 发布。

1. 唤醒并解锁模拟器，关闭已有系统验证窗口；给模拟器设置专用测试 PIN。
2. 先在 Android 设置中允许该 App 安装未知应用；运行 `RuntimeChecks` 的 `update_only` 模式，传入 `test_pin`。该模式验证菜单、持久化开关、后台更新回调、草稿延迟、旧对话框失效、真实 Android 安装器与取消路径；只取消，绝不点击安装。
3. 使用 `UpdateChecks -e seed_e2e true` 将真实候选通过产品缓存 API 保存。这是显式测试准备，会关闭自动检查并保留已下载候选。
4. 在测试外把未知来源权限设为禁止，正常打开 App 并用真实 PIN 验证，点击继续安装、前往设置，再真实开启未知来源开关。Android 可能主动杀掉 App 进程，这是本用例要覆盖的行为。
5. 返回并取消已有系统验证页，再启动独立 `RuntimeChecks -e update_recovery_only true -e test_pin "$TEST_PIN"`。检查授权状态没有被缓存、重新验证后恢复下载、系统安装器读取私有 URI、取消安装后版本不变。成功后该模式清理固定更新缓存。
6. 完成后恢复原安装来源权限、自动检查设置、验证有效期；删除专用 APK 夹具。不要在 instrumentation 运行中直接修改 REQUEST_INSTALL_PACKAGES app-op，Android 会主动终止整个测试进程。

`RuntimeChecks` 测试期间关闭自动检查，结束后恢复原偏好。`seed_e2e` 的明确修改由操作者最终还原。测试模拟数据和测试 APK 不属于正式安装包。
