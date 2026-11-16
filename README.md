# 顺程记 · Shuncheng Ledger

个人使用的 Android 顺风车收入账本。账目保存在本机，记账与备份不需要服务器、账户或订阅。项目采用 Java 与 Android 原生 View / Canvas，没有广告或第三方统计 SDK。

编写者：**Codex**。仓库维护：**RoadKai233**。

[下载安装包](https://github.com/RoadKai233/shuncheng-ledger/releases/latest) · [构建说明](BUILDING.md) · [隐私说明](PRIVACY.md) · [发布说明](RELEASING.md)

## 功能

- 记录收入、日期、时间、平台、路线、成本与备注；金额以整数分存储。
- 查看月度收入、成本、净额、今日收入与近七天趋势；筛选和搜索明细。
- 自定义平台、改名、停用/启用、删除及转移历史账目；使用稳定平台编号保持关联。
- 手机系统锁屏验证；可选每次验证、1/5/15/30 分钟或自定义 1～1440 分钟，支持立即锁定。
- 跟随系统、浅色和深色三种外观；玻璃底栏、背景折射与拖动选择。
- AES-256-GCM 加密手动备份；恢复前校验数据，事务追加缺少的记录；兼容旧版备份。
- 导出 Excel/WPS 可读的 CSV；CSV 是明文，不作为恢复格式。
- 默认自动检查 GitHub 新版本，也可关闭或手动检查。下载前由用户确认，安装由 Android 确认。

## 安装与升级

需要 Android 8.0（API 26）或更高版本。从 [Releases](https://github.com/RoadKai233/shuncheng-ledger/releases) 下载正式 APK，使用文件管理器打开安装。已有「顺路账本」的用户可以用同签名正式版覆盖升级，包名保持 `cn.personal.shunfeng`。升级前先导出加密备份，不要卸载旧版。

GitHub Actions 的 `debug` 构建仅供隔离环境测试，使用调试签名，不能覆盖正式版。正式签名私钥不在仓库中。

## 使用

收入记录入口是「记一笔收入」，实际到账收入为必填，成本为可选。净额只扣除手动记录的成本，不自动估算油耗、折旧或其它费用。

「我的」中可管理平台、切换外观、设置验证有效期和备份。验证有效期固定从最近一次成功系统验证开始计算，返回或修改分钟数不重置起点；到期不打断前台编辑，下次进入时验证。立即锁定撤销当前验证。分钟设置会保存，成功验证状态只留在当前应用进程；进程结束或手机重启后重新验证。

卸载 App、清除数据或丢失手机会使本地账目丢失。请定期导出 `.sfbook` 加密备份并自行保管密码。备份密码无法找回。恢复只追加缺少的记录，不覆盖相同 ID 的现有记录；旧备份中已删除的记录可能重新出现。

## 网络与隐私

记账数据不会上传。联网仅用于检查或下载本仓库的公开 GitHub Release。自动检查默认开启，成功解锁后至多每 12 小时检查一次，失败时静默；可在「我的」关闭自动检查或手动检查。具体请求与数据说明见 [PRIVACY.md](PRIVACY.md)。

## 开发

```sh
# 已安装 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
bash tests/run-core-tests.sh
bash build.sh
bash tests/build-runtime-tests.sh
bash tests/build-update-tests.sh
```

默认输出 `build/shuncheng-ledger-debug.apk`、`build/runtime-tests.apk` 和 `build/update-tests.apk`。完整环境、签名与测试流程见 [BUILDING.md](BUILDING.md)。

- `MainActivity.java`：原生界面、系统验证、验证有效期、外观和文件选择。
- `GlassStyle.java`：玻璃按钮、背景取样与折射、选中镜片和拖动反馈。
- `LedgerDb.java`：SQLite 账目与平台、备份校验和 CSV 导出。
- `LedgerRules.java`、`BackupCrypto.java`：字段校验、金额与备份加密。
- `tests/`：纯 Java 与 Android Instrumentation 测试，测试数据均为虚构。

每本账本最多 20000 笔，单份加密备份最多 12 MB。主要运行验证使用 Android 11 ARM64 模拟器；不同设备的系统验证和安装界面可能不同。

## 许可证

[MIT](LICENSE)。版权所有：RoadKai233 和贡献者；编写者：Codex。提交问题时请去除真实账目、备份文件、密码和个人信息。
