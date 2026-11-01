package dev.usagetime.core
import java.time.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

/** Engine-faithful synthetic slices: UNKNOWN stays app-less, INFERRED always names an app. */
class LayeredSummaryTest{
 private fun slices(z:ZoneId,seed:Int):List<UsageSlice>{
  val r=Random(seed);val start=Dates.midnight(LocalDate.of(2026,10,30),z);val end=start+3*86400000L
  val out=mutableListOf<UsageSlice>();var at=start
  while(at<end){
   val grade=when(r.nextInt(4)){0->Evidence.CONFIRMED;1->Evidence.INFERRED;else->Evidence.UNKNOWN}
   val app=when{grade==Evidence.UNKNOWN->null;grade==Evidence.INFERRED->"app${r.nextInt(4)}";r.nextInt(6)==0->null;else->"app${r.nextInt(4)}"}
   val span=20000L+r.nextInt(400000);val stop=minOf(at+span,end)
   if(stop>at)out+=UsageSlice(UsageMetric.PRIMARY,app,at,stop,grade,"TEST",setOf(EvidenceSource.TEST),emptySet(),r.nextInt(5)==0)
   at=stop+r.nextInt(60000)
  }
  return out
 }
 private fun cellMap(cells:List<MetricDayCell>)=cells.filter{it.app!=null}.associate{(it.app!! to it.evidence) to (it.millis to it.provisional)}
 @Test fun hourCellsMergeExactlyIntoDayCellsAcrossDst(){
  for(z in listOf(ZoneId.of("UTC"),ZoneId.of("America/New_York")))for(seed in 1..4){
   val data=slices(z,seed);val day=Window(Dates.midnight(LocalDate.of(2026,11,1),z),Dates.midnight(LocalDate.of(2026,11,2),z))
   val hourly=Reports.slots(day,z,Grain.HOUR).map{DailyMetrics.cells(data,UsageMetric.PRIMARY,it)}
   val merged=DailyMetrics.mergeCells(hourly,day)
   assertEquals(cellMap(DailyMetrics.cells(data,UsageMetric.PRIMARY,day)),cellMap(merged))
  }
 }
 @Test fun hourReportFromCachedCellsEqualsSliceBuild(){
  for(z in listOf(ZoneId.of("UTC"),ZoneId.of("America/New_York")))for(seed in 1..4)for(app in listOf<String?>(null,"app0")){
   val data=slices(z,seed);val w=Window(Dates.midnight(LocalDate.of(2026,11,1),z),Dates.midnight(LocalDate.of(2026,11,2),z))
   val slotCells=Reports.slots(w,z,Grain.HOUR).map{it to DailyMetrics.cells(data,UsageMetric.PRIMARY,it)}
   val actual=DailyMetrics.hourReport(slotCells,w,z,app)
   val intervals=data.filter{it.app!=null&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)}.map{Interval(it.app!!,it.start,it.end)}
   val coverage=data.filter{it.evidence==Evidence.CONFIRMED&&!it.provisional}.map{Coverage(it.start,it.end,true)}
   val built=Reports.build(intervals,coverage,w,z,Grain.HOUR,app)
   val expected=built.copy(quality=DualReports.summary(data,UsageMetric.PRIMARY,w,app),appQuality=built.apps.associate{it.app to DualReports.summary(data,UsageMetric.PRIMARY,w,it.app)})
   assertEquals(expected.buckets,actual.buckets);assertEquals(expected.apps,actual.apps)
   assertEquals(expected.apps.associate{it.app to it.millis},actual.apps.associate{it.app to it.millis})
   assertEquals(expected.coveredMillis,actual.coveredMillis);assertEquals(expected.fullyCovered,actual.fullyCovered)
   assertEquals(expected.quality,actual.quality);assertEquals(expected.appQuality,actual.appQuality)
  }
 }
 @Test fun monthReportFromMergedDayCellsEqualsAccumulator(){
  for(z in listOf(ZoneId.of("UTC"),ZoneId.of("America/New_York")))for(seed in 1..4)for(app in listOf<String?>(null,"app1")){
   val data=slices(z,seed);val w=Window(Dates.midnight(LocalDate.of(2026,10,30),z),Dates.midnight(LocalDate.of(2026,11,2),z))
   val dayCells=Reports.slots(w,z,Grain.DAY).map{it to DailyMetrics.cells(data,UsageMetric.PRIMARY,it)}
   val expected=DailyMetrics.Accumulator(w,z,Grain.MONTH,app).apply{dayCells.forEach{(d,c)->addDay(c)}}.finish()
   val monthCells=Reports.slots(w,z,Grain.MONTH).map{m->m to DailyMetrics.mergeCells(dayCells.filter{(d,_)->d.start>=m.start&&d.end<=m.end}.map{it.second},m)}
   val recorded=dayCells.sumOf{(d,c)->DailyMetrics.recordedDays(d,c,z).toLong()}
   val actual=DailyMetrics.monthReport(monthCells,w,z,app,recorded)
   assertEquals(expected,actual)
  }
 }
 @Test fun recordedDaysCountsPartialAndEmptyDaysLikeTheAccumulator(){
  val z=ZoneId.of("UTC");val w=Window(0,3*86400000L)
  val cells=Reports.slots(w,z,Grain.DAY).map{it to DailyMetrics.cells(emptyList(),UsageMetric.PRIMARY,it)}
  assertEquals(0L,cells.sumOf{(d,c)->DailyMetrics.recordedDays(d,c,z).toLong()})
 }
}
