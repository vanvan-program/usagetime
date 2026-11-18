package dev.usagetime.core

/**
 * 补记重放后是否需要重建日/小时/月汇总。
 *
 * 背景：重放会改写 [start, now) 内的区间切片，因此调用方目前一律从"起始日零点"重算
 * 当天（以及跨天时更早）的全部汇总——即使这一轮一条切片都没写。后台补记、息屏期间的
 * 定时任务、只有检查点推进而没有新事件的轮次都会付这份成本。
 *
 * 汇总只由 `usage_intervals` 派生，而该表只有三处会变：
 * 1. 重放写入新切片（[wroteSlices]）；
 * 2. 闭包把临时切片定稿（[closureAt] 非空）；
 * 3. 回退重放时先删除旧切片（[rewound]）。
 *
 * 三者都不成立时，区间表与上一轮完全一致，上一轮提交的汇总仍然精确，可以整段跳过。
 * 任一项成立则保守地走原来的全量重建路径，不改变统计口径。
 */
object SummaryRebuild {
    fun needed(wroteSlices: Boolean, closureAt: Long?, rewound: Boolean): Boolean =
        wroteSlices || closureAt != null || rewound
}
