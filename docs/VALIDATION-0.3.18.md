# 用时记 0.3.18 验证记录

验证日期：2026-11-08。以下结果来自本地 Windows 构建环境和专用 Android 模拟器，不代表真机、长期后台或所有厂商系统。

## 构建与安装包

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 通过，Lint 为 0 errors、28 warnings。
- 正式 Preview 安装包 `app-preview.apk`：13,094,256 字节，versionName 0.3.18，versionCode 26，minSdk 29，targetSdk 36。
- APK SHA-256：`8125e19ed3893b5ab7a7ba216151e82b58e4eb07e503e8351f6f0abf84445ce3`。
- APK 签名证书 SHA-256 指纹：`2787e9a025cbd6606ac2ec05415432513ada6732360ce4dc2abb22e049e938bf`，与 0.3.17 一致，可覆盖升级。
- Gradle Wrapper 发行包下载在本次环境超时，构建改用本机已安装的 Gradle 9.3.1 执行等价任务；源码和构建脚本未因此改变。

## JVM 与工具回归

- 小组件纯 JVM 数据测试 `WidgetSnapshotTest`：6 项通过。
- 统计核心 JUnit：175 项通过。
- 应用 JVM 回归：131 项通过。
- Python 发布/升级工具测试：4 项通过。
- `python tools/verify_dual_storage.py`：全部通过。
- `python tools/audit_public_tree.py`：154 个文件，0 findings。

## Android 设备测试

在专用模拟器 `emulator-5566`（AVD `ui_qa_20261108b`）执行：

```text
WidgetRenderingTest
InsightsStorageTest
BackupStreamReaderTest
BackupStreamWriterTest
HistoryRetentionTest
OK (30 tests)
```

小组件测试覆盖：

- 五个 Provider 注册，旧版 3×3 Provider 保持兼容。
- 五款小组件在浅色和深色主题下渲染，检查总用时和应用行数量。
- 最后一行的底边不超出容器，避免排行被裁切。
- 小尺寸自动降级、大字体保留总用时和可用内容。
- 首次添加时系统尺寸为 0、以及两行高度时，不错误降级为“用时速览”。
- 空状态和错误状态可读，点击可恢复。

## 桌面实际添加

在模拟器桌面通过“设置 → 桌面小组件”添加今日概览 3×3：

- 首次添加不再显示为“今日用时 / 未记录”的大面积空白速览，而是进入今日概览布局。
- 刷新广播后小组件立即更新，不再长时间停留在“正在读取”。
- 无使用情况访问权限时，中间区域显示居中的“请开启使用情况访问”，底部显示补记状态。
- 因为模拟器未授权使用情况访问，桌面上的真实数据状态无法展示应用排行；排行、图表和三行布局由设备端合成数据渲染测试覆盖。

## 旧包覆盖升级

使用 `tools/upgrade_check.py` 从 0.3.17 覆盖安装到 0.3.18，未卸载、未清除数据：

- 升级和启动耗时 18.86 秒。
- SQLite 完整性检查通过，两年合成历史逐表行数与内容摘要保持一致。
- 主题、累计开关和自动补记偏好均保留。
- 完整结果保存在本地 `.qa-temp/upgrade-0.3.18.json`，不进入公开源码。

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

## 视觉复核

渲染截图保存在本地 `.qa-temp/widget-0.3.18/`，不进入公开源码。已检查：

- 3×3 今日概览：总用时、昨日同期、24 小时走势、三行应用排行和补记时间。
- 4×3 应用排行：五行应用、占比和排行条。
- 3×2 分类分布：环图、前三分类和其余分类汇总。
- 4×2 七日趋势：七天柱形、每日数值、未记录与零值区分。
- 深浅主题对比度、大字体底部说明和小尺寸降级。

## 未覆盖范围

- 未在物理手机执行安装、权限授权、桌面宿主和长期后台验收。
- 未覆盖不同厂商 Launcher 对 3×3 单元格尺寸、预览图和固定小组件的差异。
- 小组件定时刷新仍受系统省电策略、后台限制和 WorkManager 调度影响。
- 时间线只展示仍保留明细的近期日期；长期日汇总无法反推旧时段。
