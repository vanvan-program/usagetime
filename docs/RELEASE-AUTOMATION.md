# 自动发布与覆盖升级验收

## 触发方式

- Pull Request 或手动运行 `Validate and release Android`：隐私审计、SQLite 检查、Python/JVM 回归、Lint、Android 备份/分类/小组件测试。
- 推送 `v版本号` 标签：上述检查通过后构建签名 Preview APK，对上一正式版执行覆盖升级检查，再发布正式最新版 Release。
- 标签必须与 `app/build.gradle.kts` 的 versionName 一致，versionCode 必须大于上一正式版。
- 发布说明必须位于 `docs/RELEASE-版本号.md`。源码和新增文件必须全部提交，源码压缩包从该标签对应提交生成。

## 首次签名配置

仓库维护者需要在 GitHub Actions encrypted secrets 中配置下列四项。只能使用与已发布安装包一致的原签名身份；不能临时生成新密钥，否则老用户无法覆盖升级。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 原签名 keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 对应 alias |
| `ANDROID_KEY_PASSWORD` | 对应私钥密码 |

密钥不进入 Git、源码压缩包或 Release 附件。没有配置密钥时，发布任务明确失败，不退回临时签名发布。安装包签名指纹还会与上一正式版 APK 比对。

本地构建保留原有签名方式；也可以通过 `USAGETIME_KEYSTORE_PATH`、`USAGETIME_KEYSTORE_PASSWORD`、`USAGETIME_KEY_ALIAS`、`USAGETIME_KEY_PASSWORD` 指定 Preview 构建的稳定签名。

## 覆盖升级检查

`tools/upgrade_check.py` 只接受专用 Android 模拟器，禁止连接个人手机执行。默认拒绝已有用时记安装的模拟器；维护者仅在明确可丢弃的 QA 安装上使用 `--reuse-qa-install`。

检查过程：

1. 安装上一正式版，停止应用，注入纯合成的两年历史数据库及设置。
2. 将数据库传输回来并逐字节比对，避免测试文件传输损坏。
3. 启动旧版，确认历史数据可保留。
4. 使用 `adb install -r` 覆盖安装新版；不卸载、不清除数据。
5. 检查新版成功启动、页面没有读取错误、SQLite 完整性通过，各历史表的行数和内容摘要完全相同，主题、累计开关和自动补记偏好保留。

该测试关闭自动补记，避免合成数据混入真实系统使用事件；它证明安装升级的数据保留，不替代真实授权采集、长期后台、低空间或物理断电验收。

## 本地命令

先配置 JDK、Android SDK、Gradle 和 adb，再运行：

```sh
python -m unittest discover -s tools -p "test_*.py"
python tools/audit_public_tree.py
python tools/verify_dual_storage.py
```

```sh
python tools/upgrade_check.py --serial emulator-5554 --old-apk previous.apk --new-apk app/build/outputs/apk/preview/app-preview.apk --output artifacts/upgrade-check.json
python tools/ui_smoke.py --serial emulator-5554 --output artifacts/ui
```

UI 检查通过系统控件树定位按钮，覆盖真实/示例记录、分类调整、月历与热力图，截图只保存在本地 `artifacts`，不进入公开源码。`release_package.py` 会拒绝版号不符、旧构建产物和未提交源码，输出 APK、精确提交源码、更新记录及 SHA-256。
