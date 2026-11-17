package dev.usagetime.core

/**
 * 平台事件查询的重叠窗口策略。
 *
 * 背景：补记时从"上次成功边界"往前多查一段，用来接住系统迟到上报的事件。
 * 之前固定回看 2 天，正常节奏下（上次边界≈现在）等于每次补记都重扫最近 2 天，
 * 是查询阶段耗时的主要来源。参考 Zenith / timelimit-android 等实现，连续性好时
 * 只需要分钟级重叠即可。
 *
 * 安全网：为避免"30 分钟外的迟到事件永远看不到"（见 GITHUB-REFERENCE-REVIEW-2026-10-31.md），
 * 引入周期性深核对：距上次深核对超过 [DEEP_SWEEP_INTERVAL_MS] 时，本轮强制使用回退预算。
 * 这样常见的高频打开/刷新走分钟级窗口，迟到事件最迟在一个深核对周期内被接住。
 *
 * 纯函数，便于 JVM 单元测试；不接触 Android API。
 */
object QueryOverlap {
    /** 连续性完好且非深核对时的重叠窗口。 */
    const val HEALTHY_WINDOW_MS: Long = 30L * 60_000L          // 30 分钟
    /** 任一异常标志出现、或执行深核对时的回退预算。 */
    const val FALLBACK_WINDOW_MS: Long = 2L * 86_400_000L      // 2 天
    /** 首次采集（尚无成功边界）的回溯窗口。 */
    const val FIRST_WINDOW_MS: Long = 7L * 86_400_000L         // 7 天
    /** 深核对间隔：无论连续性如何，至少每这么久用一次回退预算清理迟到事件。 */
    const val DEEP_SWEEP_INTERVAL_MS: Long = 6L * 3_600_000L   // 6 小时

    private const val DAY_MS: Long = 86_400_000L

    /**
     * 返回半开区间 `[queryStart, now)` 的下界。永不早于 `now - 7 天`，也不为负。
     *
     * @param latest 上次成功提交的事件边界时间；`null` 表示首次采集。
     * @param healthyContinuity 上一轮是否连续完整（无中断/时钟异常/空响应/长延迟）。
     * @param deepSweepDue 本轮是否到点需要做一次深核对。
     */
    fun queryStart(now: Long, latest: Long?, healthyContinuity: Boolean, deepSweepDue: Boolean): Long {
        if (latest == null) return (now - FIRST_WINDOW_MS).coerceAtLeast(0L)
        val overlap = if (healthyContinuity && !deepSweepDue) HEALTHY_WINDOW_MS else FALLBACK_WINDOW_MS
        return maxOf(latest - overlap, now - FIRST_WINDOW_MS).coerceAtLeast(0L)
    }

    /**
     * 判断刚结束的一轮是否留下了可缩短下一轮重叠的连续连续性。
     * 与既有 "GAP/SYNC_DELAY" 的判定条件保持一致（2 天）。
     */
    fun continuityIntact(
        interrupted: Boolean,
        clockDiagnosed: Boolean,
        hasLifecycle: Boolean,
        latest: Long?,
        now: Long,
        maxGapMs: Long = 2 * DAY_MS,
    ): Boolean = !interrupted && !clockDiagnosed && hasLifecycle && (latest == null || now - latest <= maxGapMs)
}
