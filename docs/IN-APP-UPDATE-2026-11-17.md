# 应用内更新、国内镜像与下载进度（2026-11-17）

## 目标

在原有「手动检查 GitHub Releases → 浏览器打开 APK 直链」的基础上，补上三件事：

1. 应用内直接下载更新 APK，并交给系统安装器；
2. 增加国内镜像下载源，检查与下载都可回退；
3. 下载过程显示进度，失败给出明确提示并允许重试/切换来源。

不改变版本判断口径、统计逻辑、数据库、备份格式与同步协议。

## 文件与职责

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/dev/usagetime/app/sync/UpdateDownloads.kt` | 下载源模式、镜像列表、URL 白名单、进度模型、下载状态、来源偏好、进度格式化 |
| `app/src/main/java/dev/usagetime/app/sync/ApkDownloader.kt` | 流式下载、进度回调、多来源回退、APK 校验（大小 + ZIP 头） |
| `app/src/main/java/dev/usagetime/app/sync/UpdateInstaller.kt` | 安装权限检查、FileProvider 安装意图、安装包包名/版本校验；`UpdateService` 负责检查更新时的镜像回退 |
| `app/src/main/java/dev/usagetime/app/AboutUpdateSection.kt` | 下载源切换、应用内下载按钮、进度条、失败/重试/安装 UI |
| `app/src/main/res/xml/file_paths.xml` | 只暴露 `cacheDir/updates` 给 FileProvider |

## 下载源

`UpdateSourceMode`：

- `自动`（默认）：先 GitHub 官方，失败后依次尝试国内镜像；
- `国内镜像优先`：只走镜像列表；
- `仅 GitHub`：不经过任何代理。

镜像均为公开只读 GitHub 代理，按前缀拼接到原始 `browser_download_url`：

- `https://ghfast.top/`
- `https://ghproxy.net/`
- `https://gh-proxy.com/`
- `https://github.moeyy.xyz/`

检查更新时也复用同一组镜像代理 `api.github.com` 路径：`自动` 模式为 GitHub 官方 + 2 个镜像回退，避免一次失败就完全无法检查。来源选择保存在 `update-settings` 的 `source-mode`，只影响更新请求，不影响其他网络行为。

## 下载流程

1. 从 Release JSON 取 `apkUrl`、`apkName`、`apkSizeBytes`；
2. 校验源地址必须属于 GitHub 或配置的镜像主机（HTTPS、无 userInfo、端口 443）；
3. 按来源模式生成候选，逐个尝试；
4. 写入 `cacheDir/updates/<name>.apk.part`，每 120ms 或结束时回调进度（已下载字节、总字节、百分比、速度）；
5. 完成后校验：文件非空、与发布大小一致、ZIP 魔数 `PK\x03\x04`；通过后改名为正式文件；
6. 单个来源失败（连接、超时、HTTP 状态、大小不符、格式不符）自动切换下一个；全部失败弹出提示并保留“重试”。

取消会删除 `.part` 临时文件。对话框关闭不取消下载，返回卡片会看到内联进度，可重新打开或取消。

## 安全边界

- 只接受 HTTPS 与白名单主机；镜像 URL 由可信 GitHub URL 前缀派生，不接受 Release JSON 里的任意主机；
- 单文件上限 300 MB；若服务端与发布信息都给出大小则必须一致；
- 下载完成后校验 ZIP 头，安装前再用 `PackageManager` 校验包名必须等于当前应用、`versionCode` 必须更高，否则拒绝并删除；
- APK 签名校验交给 Android 系统安装器（同签名才能覆盖安装），本应用不做 TOFU 之外的签名固定；
- 新增 `REQUEST_INSTALL_PACKAGES` 权限和 `FileProvider`，仅用于把下载好的 APK 交给系统安装器；未点击下载时不会请求。

## 权限与清单

- `android.permission.REQUEST_INSTALL_PACKAGES`；
- `androidx.core.content.FileProvider`，authority 为 `${applicationId}.fileprovider`；
- `res/xml/file_paths.xml` 仅开放 `cache-path updates/`。

Android 8+ 未授权「安装未知应用」时，会提示并跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`；授权后返回应用内重试即可。

## 测试

- 新增 JVM：`app/src/test/java/dev/usagetime/app/UpdateDownloadsTest.kt`
  - 来源顺序与镜像前缀拼接；
  - 下载 URL 白名单（含 http、伪主机、userInfo 拒绝）；
  - 进度比例、百分比、字节与速度格式化；
  - 本地 HTTP 服务真实流式下载并校验字节、`Verifying`/`Ready` 状态与最终进度；
  - 首个来源失败后自动切到可用来源；
  - ZIP 魔数与大小校验拒绝；
  - 错误信息映射。
- 回归：应用 JVM 192 项、核心 JVM 186 项全部通过（本机离线 Gradle 用户目录 + 只读依赖缓存）。

## 验证边界

- 未在真机执行“下载 → 安装 → 覆盖升级”全链路；`REQUEST_INSTALL_PACKAGES` 授权页、FileProvider 授权、系统安装器交互需设备验收。
- 国内镜像只做了 URL 构造与白名单单测，未在真实网络逐一探测可用性；代理服务可能变更或下线，必要时在 `UpdateMirrors.DOMESTIC` 中调整。
- 未做断点续传（失败重下），未做后台下载服务；应用进程被杀会中断下载。