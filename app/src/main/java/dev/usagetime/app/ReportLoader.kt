package dev.usagetime.app

import dev.usagetime.app.data.Records
import dev.usagetime.core.*
import dev.usagetime.core.Period
import java.time.LocalDate
import java.time.ZoneId

data class Selection(
    val page:Int=0,val period:Period=Period.WEEK,val anchor:LocalDate=LocalDate.now(),
    val custom:Pair<LocalDate,LocalDate>?=null,val app:String?=null,val detail:Boolean=false,
    val grain:Grain=Grain.MONTH,val line:Boolean=false,val monthly:Boolean=false,
    val compareA:LocalDate=LocalDate.now(),val compareB:LocalDate=LocalDate.now().minusWeeks(1),
    val same:Boolean=true,val compareApp:String?=null,val query:String="",val nameSort:Boolean=false,val metric:UsageMetric=UsageMetric.PRIMARY,val legacy:Boolean=false
){
    fun requiresReport(previous:Selection)=copy(line=previous.line,query=previous.query,nameSort=previous.nameSort)!=previous
    fun followCurrentDate(previous:LocalDate,today:LocalDate)=copy(
        anchor=if(anchor==previous)today else anchor,
        compareA=if(compareA==previous)today else compareA,
        compareB=if(compareA==previous&&compareB==(if(monthly)previous.minusMonths(1)else previous.minusWeeks(1)))
            (if(monthly)today.minusMonths(1)else today.minusWeeks(1))else compareB)
}
data class DisplayData(val report:UsageReport?=null,val other:UsageReport?=null,val all:UsageReport?=null,val first:Long?=null,val last:Long?=null,val recordedDays:Int=0,val legacyAvailable:Boolean=false,val allOther:UsageReport?=null)

/** One immutable request; callbacks retain the store's existing lock and transaction boundaries. */
internal class ReportLoader(
    private val first:suspend ()->Long?,
    private val last:suspend ()->Long?,
    private val hasLegacy:suspend (Window)->Boolean,
    private val aggregate:suspend (Window,Grain,String?)->UsageReport
){
    suspend fun load(s:Selection,clock:Long,z:ZoneId,demoData:Records?=null):DisplayData{
        val isDemo=demoData!=null
        val first=if(isDemo)demoData!!.coverage.first().start else first()
        val reports=mutableMapOf<Triple<Window,Grain,String?>,UsageReport>()
        suspend fun buildRaw(w:Window,g:Grain,pkg:String?):UsageReport{
            if(!isDemo)return aggregate(w,g,pkg)
            // The old read/readLegacy fallback was unreachable: real reports return above.
            val records=demoData!!
            if(s.legacy)return Reports.build(records.intervals,records.coverage,w,z,g,pkg)
            val observations=records.intervals.map{UsageSlice(s.metric,it.app,it.start,it.end,Evidence.INFERRED,"SYNTHETIC_DEMO",setOf(EvidenceSource.TEST),emptySet())}
            val filtered=observations.filter{it.metric==s.metric&&it.app!=null&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)}
            val report=Reports.build(filtered.map{Interval(it.app!!,it.start,it.end)},records.coverage,w,z,g,pkg)
            return report.copy(fullyCovered=false,coveredMillis=0,quality=DualReports.summary(observations,s.metric,w,pkg),appQuality=report.apps.associate{it.app to DualReports.summary(observations,s.metric,w,it.app)})
        }
        suspend fun build(w:Window,g:Grain,pkg:String?):UsageReport{
            val key=Triple(w,g,pkg)
            return reports[key]?:buildRaw(w,g,pkg).also{reports[key]=it}
        }
        return if(s.page==2){
            fun full(d:LocalDate)=if(s.monthly)Dates.fullMonth(d,z)else Dates.fullWeek(d,z)
            val (a,b)=Dates.alignedComparison(full(s.compareA),full(s.compareB),clock,z,s.same)
            DisplayData(build(a,Grain.DAY,s.compareApp),build(b,Grain.DAY,s.compareApp),all=if(s.compareApp!=null)build(a,Grain.DAY,null)else null,first=first,last=if(isDemo)clock else last(),allOther=if(s.compareApp!=null)build(b,Grain.DAY,null)else null)
        }else{
            val w=if(s.period==Period.CUSTOM&&s.custom==null)Window(clock,clock)else Dates.window(s.period,clock,z,first?:clock,s.custom,s.anchor)
            val g=when(s.period){Period.TODAY->Grain.HOUR;Period.ALL->s.grain;Period.CUSTOM->if(w.duration>90*86400000L)Grain.MONTH else Grain.DAY;else->Grain.DAY}
            val pkg=if(s.detail)s.app else null
            val report=build(w,g,pkg)
            val legacyAvailable=!isDemo&&!s.legacy&&report.apps.isEmpty()&&hasLegacy(w)
            DisplayData(report,all=if(pkg!=null)build(w,g,null)else null,first=first,last=if(isDemo)clock else last(),recordedDays=report.recordedDays?:build(w,Grain.DAY,null).buckets.count{it.available},legacyAvailable=legacyAvailable)
        }
    }
}
