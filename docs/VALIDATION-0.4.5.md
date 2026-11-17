# 0.4.5 本地验证记录

日期：2026-11-17。版本：0.4.5 / 34。数据库 v6，备份 v3。

## 构建与测试

- `:core` JVM：186 项通过（含新增 `QueryOverlapTest` 7 项、`ReportCacheTest` 新增 2 项）。
- `:app` JVM：192 项通过（含新增 `UpdateDownloadsTest` 7 项）。
- `:app:compileDebugUnitTestKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:assemblePreview`、`:app:assembleRelease`、`:app:lintDebug`：全部通过。
- Android Lint：0 errors、4 warnings（均为既有目标平台版本或依赖更新提醒，改动的更新与缓存文件无新增告警）。
- Python：`python -m unittest discover -s tools -p "test_*.py"` 5 项通过；`python tools/verify_dual_storage.py` 全部 PASS。
- 本地 Release 为未签名包；正式签名包由 GitHub Actions 在推送 `v0.4.5` 标签后构建，签名指纹一致性与 versionCode 递增由工作流校验。

## 未执行项与边界

- 未在本机运行 `connectedDebugAndroidTest`（需要模拟器或真机）；设备测试源码已编译通过，云端工作流会执行历史存储、备份与桌面组件回归。
- 未在本机执行 `tools/upgrade_check.py` 覆盖升级检查（需要专用模拟器）；由发布工作流执行。
- 应用内「下载 → 授权未知来源 → 系统安装器 → 覆盖升级」链路未在真机验收，需安装后人工确认。
- 国内镜像只验证了 URL 构造与主机白名单，未在真实网络逐个探测可用性；代理服务可能变化，可在 `UpdateMirrors.DOMESTIC` 调整。
- 更新下载不支持断点续传；应用进程被杀会中断下载，需要重新下载。
