<div align="center">

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | English

# UsageTime (用时记)

**A privacy-first, local Android app usage & screen-time statistics tracker built with Kotlin, Jetpack Compose, and Room.**

[![Release](https://img.shields.io/github/v/release/vanvan-program/usagetime?style=flat-square)](https://github.com/vanvan-program/usagetime/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?style=flat-square)](#installation--usage)
[![Kotlin](https://img.shields.io/badge/Kotlin%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#tech-stack--building-from-source)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[Latest Release](https://github.com/vanvan-program/usagetime/releases/latest) · [0.4.16 Release Notes (Chinese)](docs/RELEASE-0.4.16.md) · [Changelog](CHANGELOG.md) · [Desktop Sync](docs/SYNC.md) · [Metrics Spec](docs/STATISTICS-0.3.11.md) · [Database Notes](docs/DATABASE-0.3.12.md) · [Issues](https://github.com/vanvan-program/usagetime/issues)

</div>

---

## Overview

**UsageTime** is a local-first app usage tracker for **Android 10+**. It reads Android usage events on-device and produces hourly, daily, weekly, and monthly trend charts, app rankings and category donut charts—helping you understand where your screen time actually goes.

- **Local-First by Default**: No account required. Makes zero network requests unless you opt into cloud sync or manually tap "Check for Updates".
- **Optional E2EE WebDAV Sync**: Since `v0.3.19`, supports opt-in end-to-end encrypted WebDAV sync for modified Hindsight Mobile builds; since `v0.4.0`, optionally pulls desktop app summaries when "Show Desktop Data" is enabled (displayed separately without merging into phone totals).

## Key Features

- **Dual Metrics with Evidence Quality**:
  - Switch between **Primary Usage Time**, **Screen Visible Time**, and legacy history.
  - Explicitly surfaces *Confirmed*, *Candidate Estimate*, *Undetermined*, and *Unclosed* intervals rather than silently treating unknown gaps as zero.
- **Rich Analytics & Visualizations**: Hourly/daily/monthly rollups, period comparisons, app rankings, search & details, automatic and manual app categorization, and a donut chart with time and share by category—backed by a bounded report cache.
- **5 Home Screen Widgets**: Quick Summary, Today Overview, Top Apps, Category Breakdown, and 7-Day Trend.
- **Desktop Usage Dashboard** *(off by default)*: Displays encrypted desktop app summaries (top apps, categories, 7-day trend) independently from phone data.
- **Dual-Tier Storage & Full Backups**:
  - Retains at least 90 full calendar days + today of granular local events while preserving long-term daily dual-metric summaries with safe compaction and incremental backfill.
  - Full local JSON / gzip backup v3 (backward-compatible with v1/v2), CSV export, restore protection, and an isolated demo mode.
- **In-App Update Checker**: Manual GitHub Releases check under *Settings → About* with in-app APK download and configurable mirror fallback.
- **Adaptive UI**: Low-saturation lavender theme with outlined cards and floating bottom bar on phones, switching to a navigation rail on wide screens.

## Statistical Definitions

- **Primary Usage Time**: Estimates the app you actively engaged with using lifecycle and window-focus evidence; ambiguous or multi-window conflicts remain *Unknown*.
- **Screen Visible Time**: Accumulates per-app on-screen visibility. Overlapping windows (split-screen / PiP) count per visible app and do not equal total screen-on time.
- **OS Boundaries**: First install, revoked permissions, or aggressive OEM background limits can leave unrecoverable gaps. See [STATISTICS-0.3.11.md](docs/STATISTICS-0.3.11.md) and [DATABASE-0.3.12.md](docs/DATABASE-0.3.12.md).

## Installation & Usage

1. Download the signed APK and verify its SHA-256 checksum from [GitHub Releases](https://github.com/vanvan-program/usagetime/releases/latest).
2. Grant **Usage Access** permission to begin local tracking.
3. Always export a full backup before switching devices, uninstalling, or upgrading across major database versions.

## Privacy & Data

- **No Accounts, Offline by Default**: Raw usage events stay strictly on your device.
- **Opt-In E2EE WebDAV**: Uploads only encrypted daily dual-metric summaries and device metadata—never raw events, diagnostic logs, or screenshots. Sync does not replace full backups.
- **Clean Source Tree**: Contains source, Room schemas, synthetic tests, and docs only—no personal data, keys, or binaries. See [PUBLISHING.md](docs/PUBLISHING.md).

## Tech Stack & Building from Source

- **Stack**: Kotlin · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room `2.8.4` (KSP) · WorkManager `2.11.1` · Coroutines `1.10.2` · OkHttp `4.12.0`
- **Requirements**: JDK `21` (JVM 17 bytecode) · Android SDK `37` (`minSdk 29` / `targetSdk 36`) · Build Tools.

**Windows PowerShell**:

```powershell
./build-local.ps1
python tools/verify_dual_storage.py
```

**macOS / Linux**:

```sh
sh gradlew :app:assemblePreview :core:test :app:testDebugUnitTest :app:lintDebug
python3 tools/verify_dual_storage.py
```

- Run `python tools/audit_history_storage.py` for migration, force-stop recovery, index boundary, and 10-year synthetic capacity audits.
- Output APK: `app/build/outputs/apk/preview/`. Custom signing uses `USAGETIME_KEYSTORE_PATH`, `USAGETIME_KEYSTORE_PASSWORD`, `USAGETIME_KEY_ALIAS`, and `USAGETIME_KEY_PASSWORD`. See [RELEASE-AUTOMATION.md](docs/RELEASE-AUTOMATION.md).

## License

Original source code is licensed under **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)**.

`Copyright (C) 2026 UsageTime contributors.`

Third-party dependencies and Gradle Wrapper retain their respective licenses.
