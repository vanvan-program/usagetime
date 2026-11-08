# 用时记 0.3.17 验证记录

验证日期：2026-11-08。以下结果来自本地 Windows 构建环境和专用 Android 模拟器，不代表真机、长期后台或所有厂商系统。

## 构建与安装包

- `:app:assemblePreview` 与 `:app:lintDebug` 通过，Lint 为 0 errors、17 warnings。
- 安装包 `app-preview.apk`：13,027,520 字节，versionName 0.3.17，versionCode 25，minSdk 29，targetSdk 36。
- APK 签名 SHA-256 指纹：`2787e9a025cbd6606ac2ec05415432513ada6732360ce4dc2abb22e049e938bf`，与 0.3.16 安装包一致，可覆盖升级。
- 交付包完成后另以 `SHA256SUMS.txt` 记录文件校验值。

## JVM 与工具回归

- 统计核心 JUnit：175 项通过。
- 应用 JVM 回归：125 项通过。
- Python 发布/升级工具测试：4 项通过。
- `python tools/verify_dual_storage.py`：通过。
- `python tools/audit_public_tree.py`：132 个文件，0 findings。
- GitHub Actions 工作流 YAML 与 job 依赖检查通过。

## Android 设备测试

在专用模拟器 `emulator-5566`（AVD `ui_qa_20261108b`）执行：

```text
InsightsStorageTest
BackupStreamReaderTest
BackupStreamWriterTest
HistoryRetentionTest
OK (24 tests)
```

覆盖分类备份扩展、gzip 备份尾部截断拒绝、长期数据迁移、小组件布局与注册。测试使用的分类、备份和升级数据均为合成数据。

## 旧包覆盖升级

从 0.3.16 覆盖安装到 0.3.17，未卸载、未清除数据，应用成功启动，SQLite 完整性检查通过。两年合成历史逐表行数与内容摘要保持一致：

| 表 | 行数 |
| --- | ---: |
| `intervals` | 730 |
| `coverage` | 730 |
| `apps` | 1 |
| `usage_events` | 730 |
| `usage_intervals` | 1460 |
| `daily_usage` | 1460 |
| `history_days` | 4380 |
| `history_seals` | 1460 |

主题、累计开关和自动补记偏好均保留。安装加启动耗时约 7.72 秒。完整结果保存在本地 `.qa-temp/upgrade-0.3.17.json`，不进入公开源码。

## 界面复核

`tools/ui_smoke.py` 在专用模拟器完成 9 个页面状态截图，覆盖真实空记录、示例时间线、分类修改与汇总、月历、日期跳转和年度热力图。系统控件树未出现读取错误，检查脚本通过。

复核时确认并修复了深色卡片默认文字颜色对比度问题。桌面小组件的布局、注册和背景更新由 Android 测试覆盖，但本次自动截图未包含在桌面实际添加后的渲染结果，仍需在目标设备上人工确认。

## 未覆盖范围

- 未在物理手机执行安装、升级和权限授权验收。
- 未覆盖长期后台、省电策略、WorkManager 延迟和厂商后台限制。
- 未覆盖低存储空间、强制停止、物理断电和备份媒体损坏。
- 时间线只展示仍保留明细的近期日期；长期日汇总无法反推旧时段。
- 热力图打开时从年初开始，需横向滑动查看近期数据；月份按钮可跳转查看对应月份。
