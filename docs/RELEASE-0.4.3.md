# 用时记 0.4.3 · 关于更新检查与架构质量提升

Android 10 及以上；versionCode 32；数据库版本 6；完整备份 v3。

## 本版更新

- **设置页「关于用时记」与手动检查更新**：显示当前版本号与 Build 号，支持点击查看仓库/协议信息，以及手动从 GitHub Releases 检查最新版本；检测到新版本时弹出版本对比、APK 大小与更新日志，点击后通过系统浏览器打开 APK 直链或 Release 页面下载（不新增 `REQUEST_INSTALL_PACKAGES` 等敏感权限，未手动检查时不后台联网）。
- **稳定性、性能与省电优化**：强化 CSV 导出公式注入防护、同步水位线边界与 WebDAV/XML 体积及安全校验；精简冷热路径调度与小组件/UI 渲染开销。
- **构建与包体积优化**：启用 Release 混淆与资源裁剪，收敛精简图标与语言包资源，解决构建配置与 Android Lint 告警。
- **代码架构与测试扩充**：将 `MainActivity` 拆分为独立页面组件与可测展示模块，抽取同步公共工具并扩充 JVM 与发布脚本测试覆盖。

## 兼容说明

- 数据库版本（6）、完整备份格式（v3）与 WebDAV 同步协议均保持兼容，支持从 0.4.2 直接覆盖安装，原有设置与统计数据不受影响。

## 验证

- `:core` JVM 测试（17 类，177 项）、`:app` JVM 测试（19 类，184 项）、Python 工具单测（5 项）与 SQLite 双层存储校验（24 项）全部通过。
- Android `lintDebug`、`assemblePreview`、`minifyReleaseWithR8` 与 `compileDebugAndroidTestKotlin` 均编译/验证通过；`connectedDebugAndroidTest` 与覆盖升级测试将在 GitHub Actions CI 模拟器中执行。
