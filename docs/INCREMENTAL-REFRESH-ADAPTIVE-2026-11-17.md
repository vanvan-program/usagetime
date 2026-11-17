# 增量刷新：自适应查询窗口与按范围失效（2026-11-17）

基线：用时记 0.4.4 源码。本轮改动聚焦补记（collect）的查询预算与报表缓存失效策略，**不改变统计口径、证据规则、数据库结构、备份格式或同步协议**。

## 背景

真机实测（iQOO15、0.3.4）补记一次约 1.02~1.24 秒，其中 `query_ms 619~749ms` 是最大单项（[refresh-034-sync-timings.log](../refresh-034-sync-timings.log)、[增量刷新验证-0.3.4.json](../本地安装包/增量刷新验证-0.3.4.json)）。原因：每次普通补记固定重叠重查最近成功边界前 2 天，正常节奏下等于把最近 2 天事件重新反序列化、哈希、空插入一遍。参考同类开源实现（[GitHub 安卓计时项目实现调研](../../04_时间统计参考项目/GitHub安卓计时项目实现调研_2026-11-17.md)），Zenith 全量重叠约 10 分钟、明细路径 30 分钟，timelimit-android 仅 1.5 秒。

## 变更一：自适应查询窗口

- 新增纯函数 [QueryOverlap](../core/src/main/kotlin/dev/usagetime/core/QueryOverlap.kt)：
  - 首次采集（无成功边界）：回看 7 天；
  - 连续性完好且未到深核对点：重叠 30 分钟；
  - 权限中断 / 时钟异常 / 空响应 / 超过 2 天延迟 / 到达深核对点：回退 2 天；
  - 下界始终不早于 `now - 7 天`，也不为负。
- [UsageStore.collect](../app/src/main/java/dev/usagetime/app/data/UsageStore.kt) 使用该函数计算 `queryStart`；每轮结束用 `QueryOverlap.continuityIntact(...)` 记录 `sync-healthy`，并记录 `last-deep-sweep`。
- **深核对安全网**：距上次深核对满 6 小时即强制使用 2 天预算一次。这样高频打开/刷新走分钟级窗口，而延后上报超过 30 分钟的迟到事件最迟在一个深核对周期（≤6 小时）内被接住——回应了 [GITHUB-REFERENCE-REVIEW-2026-10-31.md](GITHUB-REFERENCE-REVIEW-2026-10-31.md) 对"过度缩短重叠会永久漏掉迟到事件"的担忧。

**正确性**：重放起点仍取 `min(最新基线, 脏边界, 最早新增事件)`（[ReplayProgress.start](../core/src/main/kotlin/dev/usagetime/core/ReplayProgress.kt)），缩短查询窗口只减少"迟到事件覆盖预算"，不影响已保存事件的重放与派生结果；长断档时 `queryStart` 以"最新边界 − 30 分钟"自然覆盖整段空缺，且不早于 7 天上限。

## 变更二：按受影响日期失效报表缓存

- [ReportCache](../core/src/main/kotlin/dev/usagetime/core/ReportCache.kt) 新增 `invalidateWhere(predicate)`：只移除命中的条目并归还权重，未命中的历史报表保留。
- 增量补记（`collect` 与本地重放 `processPendingInsideLock`）不再整表 `clear()`，改为 `invalidateReportsFrom(modifiedFrom)`：仅失效 `window.end > modifiedFrom` 的报表，然后照常递增 revision 触发 UI 刷新。
- 完整重算、恢复安全副本、备份导入等"全量变化"路径仍保留 `publishReportRevision()` 全清。

**正确性**：`modifiedFrom` 是重放实际改动的最早时间；重算覆盖 `[dayStart(modifiedFrom), now]`，其每个受影响日块的 `window.end` 都大于 `modifiedFrom`，因此一定被失效；完全早于该范围的历史报表数据未变化，可安全复用。修改期间 `collect` 全程持有互斥锁，UI/组件读取报表会阻塞到提交完成，不会读到半写状态。

## 测试

- 新增核心 JVM 测试：`QueryOverlapTest`（首 7 天、健康 30 分钟、异常/深核对回退 2 天、7 天上限、非负、连续性判定真值表），`ReportCacheTest` 增加 `invalidateWhere` 的命中释放权重与未命中保持不变。
- 新增设备测试：`IncrementalSyncTest.healthySyncShrinksOverlapAndKeepsUntouchedHistoryCached`（健康轮次窗口为分钟级、未受影响的历史报表对象在增量补记后仍命中缓存）。异常回退逻辑由 `QueryOverlapTest` 直接覆盖。
- 既有 `IncrementalSyncTest` 全部保留，覆盖迟到事件纠正、失败回滚、时钟跳变隔离、分层缓存一致性等。

## 验证边界

本轮在无真机/无模拟器的环境下完成源码与 JVM 测试；Android 设备测试（含新增两条）需在模拟器或真机执行。未改变统计结果的可比性，仍以既有设备回归与真机验收为准。
