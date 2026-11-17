# 双指标统计重构交付记录（0.2.0 候选版）

日期：2026-10-30。源代码与主机验证已完成；Android 仪器测试尚未在设备运行，iQOO 15 行为验证待完成。本记录与第一阶段只读报告分开，后者保留原项目调查证据。

## 1. 原算法与已修复缺陷

详细文件、函数、调用链和反例见 `DUAL-METRIC-PHASE1.md`。旧算法以最后一次 RESUME 覆盖单个归属、PAUSE 等同 STOP、查询游标只向前推进，既无法区分焦点与可见，也无法修正迟到事件。空响应可能无标记地延续旧应用；同毫秒事件顺序会改变结果。

新采集链：启动/恢复 → UsageModel.refresh → UsageStore.collect；后台由 UsageWorker 调用同一收集器。原始证据 → DualUsageEngine.parse → 双指标区间 → DailyMetrics → UsageModel → 原生页面。旧 UsageParser 仅保留给旧记录兼容与基线测试，不用于新采集。

已修复：多 resumed 不选最后一个；PAUSE 保留可见候选；同毫秒批处理冲突转 UNKNOWN；初始状态未知；暂定尾段独立；近期重查与去重；保留期/权限/时钟中断重置；跨查询与跨日证据闭合；主要区间全局互斥；跨时区缓存失效；事务重算；流式备份；双指标 UI/CSV。

## 2. 指标、来源与准确性范围

内部时间均为 Long 毫秒，半开区间 [start,end)。来源 USAGE_EVENTS、OWN_WINDOW、TEST、LEGACY_IMPORT 独立保存。原始系统事件同时保留 rawType；未用于计算的类型保存为 OTHER，不能推导焦点。

| 指标 | 确定 | 推断 | 无法确定 |
|---|---|---|---|
| 主要使用时间 | 本应用真实实例的公开窗口焦点证据，且屏幕/锁状态足够、边界闭合 | 唯一 resumed 包等生命周期证据；开放焦点尾段 | 多 resumed 无焦点、状态冲突、缺失边界、观测缺口 |
| 屏幕可见时间 | 有效显式焦点证明的本应用窗口区间 | resumed 或 paused 未 stopped 的生命周期可见候选 | 遮挡、小窗实际像素、其他窗口集合、锁屏/AOD 等缺少证据的部分 |

公开 UsageEvents 不提供其他应用的真实 Activity 实例标识或全局窗口焦点。包名+类名使用 UNRESOLVED 身份槽，重复/矛盾写诊断，不能声称已区分同类多实例。只有本应用实例有 UUID 和自身公开回调。因此第三方应用多数结果会是推断，分屏主要归属可能长期 UNKNOWN。这是平台观测边界，不是精度校准到系统数字。

可见候选存在不证明窗口集合完整；生产事件同时保留范围级未知，不能把候选时长当成全部屏幕的真值。不同应用可见时长可重叠，总和可超过自然时间。同应用重叠取并集。主要使用同一时段最多一个已归属应用，导入冲突（包括空闲与应用冲突）转 UNKNOWN。

未知为范围级时间并集，不能按应用分摊；确定+推断是可归属时长。可见场景中未知可能与推断同时存在，四种数字不能简单相加为墙钟时间。暂定是最终性标志，可能与推断/未知相交。无触摸超时，连续阅读和视频不因缺少触摸被停止。

## 3. 状态转换与边界

实现：core/DualUsage.kt 的 transitionTable、parse、EngineState。每个时间戳先统一生成前一半开区间，再处理整批事件；生命周期或全局冲突不选择未验证的先后顺序。

| 输入 | 转换 |
|---|---|
| RESUME | 对应身份变 RESUMED，其他身份保留；PAUSED 恢复时闭合旧可见 origin 后开启新段 |
| PAUSE | 主要候选终止；可见候选保留；清匹配焦点 |
| STOP | 移除匹配身份和焦点 |
| SCREEN_INTERACTIVE | 交互状态 YES，不能自动解锁 |
| SCREEN_NON_INTERACTIVE | 交互状态 NO；主要使用按非交互口径结束，可见像素 UNKNOWN（可能 AOD） |
| 直接物理 SCREEN_OFF | 只有明确证据输入才使用；生产 UsageEvents 不把 non-interactive 映射到它 |
| KEYGUARD_SHOWN | 锁状态 YES，旧焦点失效；无法证明全部应用不可见 |
| KEYGUARD_HIDDEN | 锁状态 NO，不自行假定屏幕状态 |
| DEVICE_SHUTDOWN / STARTUP / GAP | 重置全部知识；未闭合边界未知，不填补运行尾段 |
| 本应用 FOCUS_GAIN/LOSS | 仅真实实例、允许来源，冲突拒绝 |
| OTHER | 仅保存原始审计证据 |

锁屏可有 showWhenLocked 窗口，只有新显式焦点证据时才能归属本应用，其他窗口仍未知。SHUTDOWN 的时间是系统使用数据库持久化语义，不作为准确物理关机瞬间。

检查点保存多个身份、屏幕、锁、焦点及事件引用。无检查点从足够近期回溯起点以 UNKNOWN 开始；查询末尾未闭合会话标暂定。ClosedEvidence 按事件引用闭合前面已落盘的暂定片段，包括暂停恢复、息屏及锁屏跨块；无法证实的边界降为 UNKNOWN。

## 4. 时间、时区与历史聚合

DualIntervals 对主要区间执行扫描线互斥处理。Reports 使用 java.time 自然边界切分，支持 DST 23/25 小时日；DailyMetrics 使用日碎片精确毫秒生成日/周/月桶，不把日累计伪装成连续区间。小时视图读取原始区间。

事件保存 UTC 纪元时间与采集 zone。页面按当前选择/系统时区重分桶，跨区后历史日期归属可能改变，累计不变；每日缓存键含时区、精确边界和算法版本，重算/导入/旧暂定闭合会失效所有受影响时区缓存。不是“事件发生当地日期永久冻结”策略。

ClockDiagnosis 对比 wall、elapsedRealtime、BOOT_COUNT。回拨到已保存边界之前暂停新时长写入，保留旧记录并提示；其他跳变/重启记诊断和 GAP。不按固定系数补时，也不能恢复设备已丢失的时钟映射。重启后 elapsed 不跨启动直接相减。

## 5. 数据库与迁移

Room v1 → v2，DualMigration.FROM_1_TO_2 仅添加以下表和索引：

- usage_events：原始来源、身份、系统类型、采集时区、导入来源。
- usage_intervals：指标、应用/未知、半开区间、证据级别、原因、引用、暂定、算法版本。
- daily_usage：按时区/自然日的双指标精确汇总与质量摘要缓存。
- sync_checkpoints：完整状态、成功边界、时钟样本、时区和算法版本。
- diagnostic_logs：中断、冲突、查询与重算诊断。

v1 intervals、coverage、cursor、apps 原样保留，不清空、不复制成两个新指标、不升级为确定。在 UI 的“旧版历史（未分级）”独立查看；缺失原始证据无法准确重建旧焦点/可见时间。

同步在一个 Room 事务内插入去重原始事件、替换 algorithm=2 派生范围、闭合旧证据、更新每日缓存与检查点。失败整体回滚。导入档案与 v1 历史不被实时重算删除；有本机重新计算覆盖的范围优先本机证据，保留档案本体。

没有自动删历史策略。每日缓存避免全范围逐桶读取多年事件，重算按日块限制临时内存；备份逐 SQLite 游标流式写出/逐记录流式导入。已测试十年日数据累计，但尚未实测十年真机数据库的容量、耗时、耗电与备份恢复；不能承诺任意事件量的性能。

## 6. 同步、重算与备份

首次请求最近 7 天；之后按连续性自适应：连续性好且未到深核对点时只重叠请求最近成功边界前 30 分钟，出现权限中断、时钟异常、空响应、超过 2 天延迟或到达 6 小时深核对点时回退到最近成功边界前 2 天，并始终受最近 7 天回溯预算约束。这是查询策略，不是 Android 保留时长保证；周期性深核对保证 30 分钟之外迟到的事件最迟在一个深核对周期内被接住。检查点恢复、已保存原始事件回放可超出系统仍保留的部分。无法观测的 OEM 静默丢事件不能通过非空响应证明不存在。

启动、恢复及每 6 小时 WorkManager 任务同步。Worker 不是精确闹钟；设备锁定/暂时不可查询异常触发重试。权限中断写入标志，恢复后置 GAP；超过保留预算的断档、时钟变化同样重置。已保存数据不依赖后台进程一直运行，尚未补记的数据可能因 OEM 长期限制而不可恢复。

设置中“重算已保存历史”仅使用本机保存原始证据，事务内重建派生数据/检查点，不能找回系统已删事件。暂停采集、导入、重算均受互斥控制。

JSON 是流式逻辑数据库备份，不是原始 .db 文件镜像。包含旧表历史、原始事件、双指标区间、检查点和诊断；每日缓存可再生成。恢复不激活外来的实时检查点，导入事件作为档案来源不参与本机回放；导入确定段降为推断并保留原来源/证据说明。重复导入按身份去重。超出单字段/总条数安全预算会事务失败，旧数据保留。

CSV 导出当前范围、当前指标及质量毫秒。首条“范围汇总”即使没有应用行也保存 UNKNOWN；后续“应用”行只写该应用数值。未知只在汇总行出现，不能把汇总与应用行重复相加。旧版未分级证据列为空，不伪报确定零。旧版未分级记录不能解释为双指标已确认结果。应用卸载不删除包名历史；名称尽可能使用缓存，重新安装同包名仍沿用历史包归属，不等于可识别同包不同开发者。

## 7. 验证与设备边界

实际命令：`./build-local.ps1 -Variant Preview`（Android APK、lintDebug、编译后 JUnitCore）；`python tools/verify_dual_storage.py`；启用共享工具后 `gradle :app:assembleDebugAndroidTest`。

本次实际结果：48 项核心 JUnit 通过；6 项主机 SQLite 检查通过；Preview 和 Android 仪器测试包构建成功。Lint 为 0 错误、9 项警告（目标 SDK/可更新依赖/数据提取配置与 KTX 建议），并非无警告。

自动化结果与安装包 SHA256 以 `本地安装包/双指标验证结果.json` 为准。核心测试覆盖连续使用、切换、多 Activity/多窗口、paused 可见、无触摸、冲突、重复与乱序、缺失边界、锁屏/非交互/AOD、重启、跨日/DST/时区、时钟异常、引用恢复、跨块闭合、导入冲突及十年日聚合。

主机真实 SQLite 执行生产迁移 SQL，对照 Room 导出 v2 schema；验证四张旧表保留、事件去重、事务中断回滚、重复派生重算不累加。另验证生产 DAO 只闭合相同指标和精确证据引用，以及闭合旧证据后所有受影响时区缓存失效。这些不能替代 Android Room 运行。仪器测试含真实 Room 迁移、事务回滚、备份往返、状态编码；仅编译通过，尚未执行。原生 UI 自动化也尚未执行。

iQOO 15 真机清单见 IQOO15-VALIDATION.md。构建成功不表示权限、分屏、悬浮窗、24/72 小时后台或厂商刷新延迟已经验收。

## 8. 修改文件与后续建议

新增：core 的 DualUsage.kt、DualIntervals.kt、DailyMetrics.kt、DualUsageTest.kt；app 的 data/DualStorage.kt、androidTest/DualStorageTest.kt、schemas/2.json；tools/verify_dual_storage.py；本交付记录和真机清单。

修改：core/Models.kt；app/data/UsageStore.kt；UsageModel.kt；MainActivity.kt；UsageTimeApplication.kt；UsageWorker.kt；app/build.gradle.kts；androidTest/UsageFlowTest.kt；build-local.ps1；README.md；ui-preview/DESIGN.md。

优先下一步：在 iQOO 15 升级安装后实际运行 Room/原生 UI 测试；用脱敏事件与动作时间线验证多窗口和后台缺口；再做长期数据库压力与耗电测量。未采用 Root、隐藏接口、无障碍服务或联网采集，也未发布 GitHub。历史“GitHub上传准备”副本尚未随本候选版更新，不能用它代表当前源代码。

官方依据：
- [UsageEvents.Event：公开事件与 shutdown/non-interactive 语义](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [UsageStatsManager：近期事件与解锁限制](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [多窗口与多 resumed](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode)
- [本应用的 top-resumed 回调](https://developer.android.com/reference/android/app/Activity#onTopResumedActivityChanged(boolean))
