# 发布正式版本

公开 CI 只编译和测试调试包，不读取正式私钥，不自动签发或发布正式版。正式 APK 使用维护者本地保存的原签名。

## 准备

1. 更新 `AndroidManifest.xml` 的 `versionCode` 和 `versionName`，同步应用内版本说明。
2. 运行核心测试、编译检查和针对变更的隔离模拟器测试；确认从旧版覆盖安装可保留数据。
3. 使用原发布密钥显式执行 release 构建，验证 APK 签名与上一版一致。
4. 确认待公开文件不含真实账目、备份、签名私钥、密码、令牌或本机绝对路径。

```sh
BUILD_TYPE=release \
SIGNING_DIR="/private/path/to/existing-signing" \
APK_FILE="dist/shuncheng-ledger-1.6.apk" \
bash build.sh
```

上例版本号仅作演示，实际发布时须与 Manifest 和 tag 一致。不要把调试 APK 重命名为正式 APK。

## 更新清单

更新客户端读取 `RoadKai233/shuncheng-ledger` 最新正式 GitHub Release 的 `update.json` 资产；APK 必须在同一 Release 的资产列表中，并使用相同 URL 与大小。资产文件名采用 ASCII，例如 `shuncheng-ledger-1.6.apk`。

准备一份 UTF-8 更新说明文件，再生成清单：

```sh
python3 tools/make-update-manifest.py \
  --apk dist/shuncheng-ledger-1.6.apk \
  --tag v1.6 \
  --notes-file release-notes.txt \
  --output dist/update.json
```

脚本从当前 Manifest 读取版本号，从最终 APK 计算 SHA-256 和大小；不会读取签名材料。请先完成最终签名再生成清单，之后不要修改 APK。发布前将 APK 内实际版本信息与 Manifest 再次核对。

`update.json` 包含以下字段：

| 字段 | JSON 类型 | 含义 |
| --- | --- | --- |
| `versionCode` | 整数 | 正整数版本代码，应大于上一正式版 |
| `versionName` | 字符串 | 显示版本 |
| `apkUrl` | 字符串 | 本仓库当前 Release 中 APK 资产的 HTTPS URL |
| `sha256` | 字符串 | APK 的 64 位小写十六进制 SHA-256 |
| `size` | 整数 | 最终 APK 字节数 |
| `notes` | 字符串 | 用户可读更新说明 |

## 上传

在 GitHub 创建相应 tag 的 Release，上传正式 APK、`update.json` 和可选的校验文件。更新客户端针对最新正式 Release，草稿和预发布版本不作为默认更新通道。

发布后确认最新 Release 的两个资产可以正常下载，核对大小与校验值，再从旧版进行一次手动检查更新。用户确认下载后仍由 Android 确认安装，账目不上传。

源码、MIT 许可证和构建脚本可以公开；发布密钥与密码始终保留在维护者自己的安全位置。不要在 GitHub Actions artifacts、源码 ZIP 或 Release 中附带签名目录。
