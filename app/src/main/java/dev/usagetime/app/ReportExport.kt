package dev.usagetime.app

import dev.usagetime.core.UsageMetric
import dev.usagetime.core.UsageReport
import java.time.ZoneId

internal data class ReportExportSnapshot(
    val report:UsageReport,
    val metric:UsageMetric,
    val legacy:Boolean,
    val includeConfirmed:Boolean
){
    val metricLabel:String get()=if(legacy)"旧版未分级" else metric.label
    val basisLabel:String get()=if(legacy)"旧版未分级" else if(includeConfirmed)"已确认与候选估计" else "仅候选估计"
}

/** Loads an immutable snapshot for the current settings instead of reusing the rendered page's report. */
internal object ReportExportRequest {
    fun selection(current:Selection,lastReport:Selection?):Selection =
        (lastReport?:current.copy(page=0)).copy(metric=current.metric,legacy=current.legacy)

    suspend fun load(
        demo:Boolean,
        selection:Selection,
        includeConfirmed:Boolean,
        clock:Long,
        zone:ZoneId,
        loader:suspend (Selection,Boolean,Long,ZoneId)->DisplayData
    ):ReportExportSnapshot{
        if(demo)error("示例数据不能导出为真实报表")
        val result=loader(selection,includeConfirmed,clock,zone)
        return ReportExportSnapshot(
            report=result.report?:error("暂无记录"),
            metric=selection.metric,
            legacy=selection.legacy,
            includeConfirmed=includeConfirmed)
    }
}

internal fun DisplayData.invalidateReport()=DisplayData(first=first,last=last)

internal object CsvReportFormat {
    fun build(snapshot:ReportExportSnapshot,label:(String)->String):String{
        fun cell(text:String)="\""+(if(text.startsWith("=")||text.startsWith("+")||text.startsWith("-")||text.startsWith("@"))"'$text" else text).replace("\"","\"\"")+"\""
        val report=snapshot.report
        val metric=cell(snapshot.metricLabel)
        val q=report.quality
        val basis=cell(snapshot.basisLabel)
        val note=cell("候选可能与未知覆盖重叠，不能相加；未闭合不计入累计；毫秒是事件记录单位")
        val summary="范围汇总,$metric,所选范围,,${report.total},${q?.confirmed?:""},${q?.inferred?:""},${q?.unknown?:""},${q?.provisional?:""},$basis,$note"
        val rows=report.apps.map{val aq=report.appQuality[it.app];"应用,$metric,${cell(label(it.app))},${cell(it.app)},${it.millis},${aq?.confirmed?:""},${aq?.inferred?:""},,${aq?.provisional?:""},$basis,$note"}
        return "\uFEFF记录类型,指标,应用,包名,统计累计毫秒,已确认毫秒,候选估计毫秒,范围未知覆盖毫秒,未闭合覆盖毫秒,累计口径,口径说明\r\n"+(listOf(summary)+rows).joinToString("\r\n")
    }
}
