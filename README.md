<div align="center">

简体中文 | [繁體中文](README.zh-TW.md) | [English](README.en.md)

# 用时记 (UsageTime)

**本地优先、隐私透明的 Android 应用使用时间统计与多维分析工具**

[![Release](https://img.shields.io/github/v/release/vanvan-program/usagetime?style=flat-square)](https://github.com/vanvan-program/usagetime/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?style=flat-square)](#安装与使用)
[![Kotlin](https://img.shields.io/badge/Kotlin%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#技术栈与从源码构建)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[下载最新正式版](https://github.com/vanvan-program/usagetime/releases/latest) · [0.4.13 更新说明](docs/RELEASE-0.4.13.md) · [历史更新记录](CHANGELOG.md) · [桌面同步说明](docs/SYNC.md) · [统计口径说明](docs/STATISTICS-0.3.11.md) · [数据库说明](docs/DATABASE-0.3.12.md) · [问题反馈](https://github.com/vanvan-program/usagetime/issues)

</div>

---

## 项目定位

**用时记**是一款支持 **Android 10 及以上**的手机应用使用时间统计工具。它在本地读取系统使用事件并完成统计分析，生成小时、日、周、月趋势图表、应用排行与年度热力图，帮助你清晰掌握日常屏幕时间分布。

- **默认完全本地运行**：无需注册账号；未开启云端同步且未主动点击检查更新时，不发起任何联网请求。
- **可选端到端加密同步**：0.3.19 起支持自愿开启 WebDAV 加密同步（供 Hindsight Mobile 修改版查看）；0.4.0 起支持在设置中手动开启「显示电脑数据」，从 Hindsight 修改版拉取桌面应用汇总并独立展示（不与手机用时相加）。

## 核心功能

- **双统计口径与证据分级**：
  - 支持「主要使用时间」「屏幕可见时间」及旧版历史三种口径切换。
  - 区分「已确认 / 候选估计 / 无法确定 / 未闭合」状态，明确标注证据质量，不把未知时间当作零。
- **多维报表与可视化**：支持按小时 / 日 / 月汇总、趋势对比、应用排行、搜索详情、手动应用分类、当天时间线、月历与年度热力图；内置报表缓存以提升浏览流畅度。
- **五款桌面小组件**：用时速览、今日概览、应用排行、分类分布、七日趋势。
- **电脑用时独立看板**（默认关闭）：按需从 WebDAV 拉取端到端加密的桌面应用汇总，独立展示排行、分类分布与近七日趋势。
- **双层存储与完整备份**：
  - 至少保留最近 90 个完整自然日及今日的本机明细，同时长期保存每日双指标汇总；支持增量补记、失败重试及受保护的清理流程。
  - 支持本地 JSON / gzip 完整备份 v3（含长期汇总与可选应用分类，兼容 v1/v2）、CSV 导出、数据库恢复保护与隔离示例模式。
- **应用内检查更新**：在「设置 → 关于用时记」手动检查 GitHub Releases，支持应用内下载与自动切换国内镜像源（自动 / 国内镜像优先 / 仅 GitHub）。
- **自适应界面**：手机端采用低饱和薰衣草紫主题、描边卡片与悬浮底栏；宽屏设备自动切换侧边导航。

## 统计口径说明

- **主要使用时间**：依据可获取的应用生命周期与窗口焦点证据，估计真正投入使用的应用时长；证据不足或多窗口冲突时保留未知状态。
- **屏幕可见时间**：按应用累计处于屏幕可见状态的时间，分屏或画中画场景下多个应用可能重叠，不等同于系统亮屏时长。
- **系统限制说明**：Android 后台策略、首次安装、权限中断或长期未补记可能产生无法恢复的记录缺口。详尽边界与验证见 [STATISTICS-0.3.11.md](docs/STATISTICS-0.3.11.md) 与 [DATABASE-0.3.12.md](docs/DATABASE-0.3.12.md)。

## 安装与使用

1. 前往 [GitHub Releases 最新版](https://github.com/vanvan-program/usagetime/releases/latest) 下载正式版 APK（附更新说明与 SHA-256 校验值）。
2. 安装后授予「使用情况访问」权限即可开始本地统计。
3. 换机、升级数据库大版本或卸载前，请先在应用内导出完整备份。

## 隐私与数据

- **无账号、默认离线**：所有使用记录保存在本机私有数据库。
- **WebDAV 端到端加密**：仅在用户主动开启后上传加密后的每日双指标汇总与设备清单；同步不包含原始事件、诊断日志或截图，且不替代完整备份。
- **源码树纯净承诺**：仓库仅含源码、Schema、合成测试与文档，不含个人数据、截图、密钥或安装包，详见 [发布内容规则](docs/PUBLISHING.md)。

## 技术栈与从源码构建

- **技术栈**：Kotlin · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room `2.8.4` (KSP) · WorkManager `2.11.1` · Coroutines `1.10.2` · OkHttp `4.12.0`
- **环境要求**：JDK `21`（产物兼容 JVM 17）· Android SDK `37`（`minSdk 29` / `targetSdk 36`）· Build Tools；通过 `JAVA_HOME`、`ANDROID_HOME` 或本地 `local.properties` 配置。

**Windows PowerShell**：

```powershell
./build-local.ps1
python tools/verify_dual_storage.py
```

**macOS / Linux**：

```sh
sh gradlew :app:assemblePreview :core:test :app:testDebugUnitTest :app:lintDebug
python3 tools/verify_dual_storage.py
```

- 也可运行 `python tools/audit_history_storage.py` 执行历史迁移、强退恢复、索引边界与十年合成容量测试。
- 产物位于 `app/build/outputs/apk/preview/`。通过 `USAGETIME_KEYSTORE_PATH`、`USAGETIME_KEYSTORE_PASSWORD`、`USAGETIME_KEY_ALIAS`、`USAGETIME_KEY_PASSWORD` 可配置正式签名；发版流程见 [RELEASE-AUTOMATION.md](docs/RELEASE-AUTOMATION.md)。

## 开源协议

本项目自行编写的源码采用 **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)** 开源。

`Copyright (C) 2026 UsageTime contributors.`

第三方依赖与 Gradle Wrapper 保留各自的许可证及版权声明。
