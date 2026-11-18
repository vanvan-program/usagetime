# 0.4.6 本地验证记录

日期：2026-11-18。版本：0.4.6 / 35。数据库 v6，备份 v3。

## 构建与测试

- `:core` JVM：190 项通过（19 个测试类；含新增 `SummaryRebuildTest` 4 项）。
- `:app` JVM：192 项通过（20 个测试类）。
- `:core:compileTestKotlin`、`:app:compileDebugKotlin`、`:app:compilePreviewKotlin`、`:app:compileDebugUnitTestKotlin`、`:app:assemblePreview`：全部通过。
- Preview 安装包 14,037,970 字节；打包后清单为 `versionCode="35"`、`versionName="0.4.6"`。
- Python：`tools/audit_public_tree.py` 检查 216 个文件，0 项发现；`tools/verify_dual_storage.py` 全部 PASS；`python -m unittest discover -s tools -p "test_*.py"` 5 项通过。

## 未执行项与边界

- 本机 `:app:lintDebug` 在 `generateDebugAndroidTestLintModel` 因离线依赖缓存缺少 `androidx.test.espresso:espresso-core:3.5.0` 等 androidTest 依赖失败，属既有环境限制（0.4.5 同样如此），云端工作流联网执行完整 Lint 与 androidTest 编译。
- 未在本机运行 `connectedDebugAndroidTest`（需要模拟器或真机）；设备测试源码未改动，云端工作流执行历史存储、备份与桌面组件回归。
- 未在本机执行 `tools/upgrade_check.py` 覆盖升级检查（需要专用模拟器）；由发布工作流执行。
- 本地 Release 为未签名包；正式签名包由 GitHub Actions 在推送 `v0.4.6` 标签后构建，并与 0.4.5 正式包比对签名指纹和 versionCode 递增。
- 「打开应用不再等待安全副本」是时序改动，本机没有真机计时数据；预期收益是消除每 12 小时一次、约 1–3 秒的交互等待，需安装后人工确认。安全副本更新间隔相应变为约 12–18 小时。
- 无变化跳过只在完全没有写入区间时生效；正常打开应用会写入自身前台尾段切片，因此前台打开仍会重建当天汇总。
