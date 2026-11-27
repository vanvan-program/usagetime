<div align="center">

[简体中文](README.md) | 繁體中文 | [English](README.en.md)

# 用時記 (UsageTime)

**本機優先、隱私透明的 Android 應用程式使用時間統計與多維分析工具**

[![Release](https://img.shields.io/github/v/release/vanvan-program/usagetime?style=flat-square)](https://github.com/vanvan-program/usagetime/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?style=flat-square)](#安裝與使用)
[![Kotlin](https://img.shields.io/badge/Kotlin%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#技術棧與從原始碼建置)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[下載最新正式版](https://github.com/vanvan-program/usagetime/releases/latest) · [0.4.14 更新說明](docs/RELEASE-0.4.14.md) · [歷史更新紀錄](CHANGELOG.md) · [桌面同步說明](docs/SYNC.md) · [統計口徑說明](docs/STATISTICS-0.3.11.md) · [資料庫說明](docs/DATABASE-0.3.12.md) · [問題回報](https://github.com/vanvan-program/usagetime/issues)

</div>

---

## 專案定位

**用時記**是一款支援 **Android 10 及以上**的手機應用程式使用時間統計工具。於本機讀取系統使用事件並完成統計分析，產生小時、日、週、月趨勢圖表、應用程式排行與年度熱力圖，協助你清楚掌握日常螢幕時間分布。

- **預設完全本機運作**：無須註冊帳號；未開啟雲端同步且未主動點擊檢查更新時，不發起任何網路連線。
- **選用端對端加密同步**：自 0.3.19 起可自願開啟 WebDAV 加密同步（供 Hindsight Mobile 修改版檢視）；自 0.4.0 起可在設定中手動開啟「顯示電腦資料」，從 Hindsight 修改版拉取桌面應用程式彙總並獨立呈現（不與手機用時相加）。

## 核心功能

- **雙統計口徑與證據分級**：
  - 支援「主要使用時間」「螢幕可見時間」及舊版歷史三種口徑切換。
  - 區分「已確認 / 候選估計 / 無法確定 / 未閉合」狀態，明確標示證據品質，不將未知時間視為零。
- **多維報表與視覺化**：支援小時 / 日 / 月彙總、趨勢對比、應用程式排行、搜尋詳情、手動分類、當日時間軸、月曆與年度熱力圖；內建報表快取提升瀏覽流暢度。
- **五款桌面小工具**：用時速覽、今日概覽、應用排行、分類分布、七日趨勢。
- **電腦用時獨立看板**（預設關閉）：按需透過 WebDAV 拉取端對端加密的桌面應用程式彙總，獨立展示排行、分類分布與近七日趨勢。
- **雙層儲存與完整備份**：
  - 至少保留最近 90 個完整自然日及今日的本機明細，並長期保存每日雙指標彙總；支援增量補記、失敗重試與受保護的清理流程。
  - 支援本機 JSON / gzip 完整備份 v3（含長期彙總與選用分類，相容 v1/v2）、CSV 匯出、資料庫還原保護與隔離範例模式。
- **應用程式內檢查更新**：於「設定 → 關於用時記」手動檢查 GitHub Releases，支援程式內下載與自動切換鏡像來源（自動 / 國內鏡像優先 / 僅 GitHub）。
- **自適應介面**：手機端採用低飽和薰衣草紫主題、描邊卡片與懸浮底欄；寬螢幕裝置自動切換側邊導覽。

## 統計口徑說明

- **主要使用時間**：依可取得的應用程式生命週期與視窗焦點證據，估計實際投入使用的時長；證據不足或多視窗衝突時保留未知狀態。
- **螢幕可見時間**：依應用程式累計處於螢幕可見狀態的時間計算，分割畫面或子母畫面下多個應用程式可能重疊，不等同於系統亮屏時長。
- **系統限制說明**：Android 背景策略、首次安裝、權限中斷或長期未補記可能造成無法恢復的紀錄缺口。詳見 [STATISTICS-0.3.11.md](docs/STATISTICS-0.3.11.md) 與 [DATABASE-0.3.12.md](docs/DATABASE-0.3.12.md)。

## 安裝與使用

1. 前往 [GitHub Releases 最新版](https://github.com/vanvan-program/usagetime/releases/latest) 下載正式版 APK（附更新說明與 SHA-256 校驗值）。
2. 安裝後授予「使用情況存取」權限即可開始本機統計。
3. 更換裝置、升級資料庫大版本或解除安裝前，請先於應用程式內匯出完整備份。

## 隱私與資料

- **無帳號、預設離線**：所有使用紀錄皆儲存於本機私有資料庫。
- **WebDAV 端對端加密**：僅在使用者主動開啟後上傳加密後的每日雙指標彙總與裝置清單；同步不含原始事件、診斷日誌或截圖，亦不取代完整備份。
- **原始碼純淨承諾**：儲存庫僅含原始碼、Schema、合成測試與文件，不含個人資料、截圖、金鑰或安裝包，詳見 [發布內容規則](docs/PUBLISHING.md)。

## 技術棧與從原始碼建置

- **技術棧**：Kotlin · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room `2.8.4` (KSP) · WorkManager `2.11.1` · Coroutines `1.10.2` · OkHttp `4.12.0`
- **環境需求**：JDK `21`（產物相容 JVM 17）· Android SDK `37`（`minSdk 29` / `targetSdk 36`）· Build Tools；透過 `JAVA_HOME`、`ANDROID_HOME` 或本機 `local.properties` 設定。

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

- 亦可執行 `python tools/audit_history_storage.py` 驗證歷史遷移、強制退出復原、索引邊界與十年合成容量測試。
- 產物位於 `app/build/outputs/apk/preview/`。透過 `USAGETIME_KEYSTORE_PATH`、`USAGETIME_KEYSTORE_PASSWORD`、`USAGETIME_KEY_ALIAS`、`USAGETIME_KEY_PASSWORD` 可設定正式簽章；發版流程詳見 [RELEASE-AUTOMATION.md](docs/RELEASE-AUTOMATION.md)。

## 開源授權

本專案自行撰寫之原始碼採用 **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)** 授權。

`Copyright (C) 2026 UsageTime contributors.`

第三方相依套件與 Gradle Wrapper 保留各自之授權條款及版權聲明。
