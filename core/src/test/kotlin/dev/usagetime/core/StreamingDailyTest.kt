package dev.usagetime.core
import java.time.*
import org.junit.Test
import org.junit.Assert.*
class StreamingDailyTest{
 @Test fun databaseReducedDaysPreserveFilteredAvailabilityQualityAndDst(){
  val z=ZoneId.of("America/New_York");val date=LocalDate.of(2026,3,6)
  val w=Window(Dates.midnight(date,z),Dates.midnight(date.plusDays(5),z))
  val days=Reports.slots(w,z,Grain.DAY).mapIndexed{i,slot->
   listOf(
    MetricDayCell(slot.start,slot.end,"a",Evidence.CONFIRMED,if(i==0)1000 else 0,0),
    MetricDayCell(slot.start,slot.end,"b",Evidence.INFERRED,if(i==4)2000 else 0,if(i==4)50 else 0),
    MetricDayCell(slot.start,slot.end,"b",Evidence.UNKNOWN,0,30),
    MetricDayCell(slot.start,slot.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,if(i==1)0 else slot.duration,80),
    MetricDayCell(slot.start,slot.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,if(i==1)slot.duration else 500,0))
  }
  for(g in listOf(Grain.DAY,Grain.WEEK,Grain.MONTH))for(app in listOf(null,"a","b","absent"))
   for(confirmed in listOf(false,true))for(legacy in listOf(false,true)){
    val old=DailyMetrics.Accumulator(w,z,g,app,legacy,confirmed)
    val reduced=DailyMetrics.Accumulator(w,z,g,app,legacy,confirmed)
    days.forEach{cells->
     old.addDay(cells)
     val summary=cells.single{it.app==DailyMetrics.SUMMARY}
     val attributed=cells.filter{it.app!=null&&it.app!=DailyMetrics.SUMMARY&&it.app!=DailyMetrics.OBSERVED&&it.evidence in listOf(Evidence.CONFIRMED,Evidence.INFERRED)}
     reduced.addDayTotals(Window(summary.start,summary.end),
      attributed.filter{(app==null||it.app==app)&&(confirmed||it.evidence!=Evidence.CONFIRMED)}.sumOf{it.millis},
      summary.millis,summary.provisional,cells.single{it.app==DailyMetrics.OBSERVED}.millis,attributed.any{it.millis>0})
     cells.filter{it.app!=null&&it.app!=DailyMetrics.SUMMARY&&it.app!=DailyMetrics.OBSERVED}.groupBy{it.app!!}.forEach{(pkg,rows)->
      reduced.addAppTotals(pkg,rows.filter{it.evidence==Evidence.CONFIRMED}.sumOf{it.millis},rows.filter{it.evidence==Evidence.INFERRED}.sumOf{it.millis},rows.sumOf{it.provisional})
     }
    }
    assertEquals("$g/$app/$confirmed/$legacy",old.finish(),reduced.finish())
   }
 }
 @Test(timeout=15000) fun tenYearsAndHundredAppsDoNotNeedAWholeHistoryList(){
  val z=ZoneId.of("UTC");val start=LocalDate.of(2016,1,1);val end=start.plusYears(10)
  val w=Window(Dates.midnight(start,z),Dates.midnight(end,z));val a=DailyMetrics.Accumulator(w,z,Grain.MONTH)
  var d=start;var days=0L
  while(d<end){val from=Dates.midnight(d,z);val until=Dates.midnight(d.plusDays(1),z)
   a.addDay((0 until 100).map{MetricDayCell(from,until,"app$it",Evidence.INFERRED,60001,0)}+MetricDayCell(from,until,DailyMetrics.SUMMARY,Evidence.UNKNOWN,until-from,0))
   d=d.plusDays(1);days++
  }
  val r=a.finish();assertEquals(120,r.buckets.size);assertEquals(100,r.apps.size);assertEquals(days*100*60001,r.total);assertEquals(r.total,r.buckets.sumOf{it.millis})
 }
 @Test fun streamingEqualsExistingDailyReportIncludingQuality(){
  val z=ZoneId.of("UTC");val w=Window(0,2*86400000L)
  val first=listOf(MetricDayCell(0,86400000,"a",Evidence.CONFIRMED,1000,0),MetricDayCell(0,86400000,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0),MetricDayCell(0,86400000,DailyMetrics.OBSERVED,Evidence.CONFIRMED,86400000,0))
  val second=listOf(MetricDayCell(86400000,w.end,"a",Evidence.INFERRED,2000,100),MetricDayCell(86400000,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,500,100),MetricDayCell(86400000,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,0,0))
  val a=DailyMetrics.Accumulator(w,z,Grain.DAY);a.addDay(first);a.addDay(second)
  assertEquals(DailyMetrics.report(first+second,w,z,Grain.DAY),a.finish())
 }
 @Test fun fragmentsAtDstBoundaryRemainExact(){
  val z=ZoneId.of("America/New_York");val d=LocalDate.of(2026,11,1);val w=Window(Dates.midnight(d,z),Dates.midnight(d.plusDays(1),z));assertEquals(25*3600000L,w.duration)
  val a=DailyMetrics.Accumulator(w,z,Grain.DAY);val mid=w.start+6*3600000L
  a.addDay(listOf(MetricDayCell(w.start,mid,"a",Evidence.INFERRED,mid-w.start,0),MetricDayCell(w.start,mid,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0)))
  a.addDay(listOf(MetricDayCell(mid,w.end,"a",Evidence.INFERRED,w.end-mid,0),MetricDayCell(mid,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0)))
  assertEquals(w.duration,a.finish().total)
 }
 @Test fun missingDaysAreUnknownRatherThanZeroCoverage(){
  val a=DailyMetrics.Accumulator(Window(0,86400000),ZoneId.of("UTC"),Grain.DAY)
  assertEquals(86400000L,a.finish().quality!!.unknown);assertFalse(a.finish().buckets.single().available)
 }
 @Test(expected=IllegalArgumentException::class) fun duplicateDayIsRejectedInsteadOfDoubleCounted(){
  val a=DailyMetrics.Accumulator(Window(0,86400000),ZoneId.of("UTC"),Grain.DAY)
  val day=listOf(MetricDayCell(0,86400000,DailyMetrics.SUMMARY,Evidence.UNKNOWN,86400000,0));a.addDay(day);a.addDay(day)
 }
 @Test fun legacyPartialCoverageKeepsItsExistingDisplaySemantics(){
  val w=Window(0,86400000);val z=ZoneId.of("UTC")
  val a=DailyMetrics.Accumulator(w,z,Grain.DAY,legacyCoverage=true)
  a.addDay(listOf(MetricDayCell(0,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,w.duration-1000,0),MetricDayCell(0,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,1000,0)))
  assertEquals(Reports.build(emptyList(),listOf(Coverage(0,1000,true)),w,z,Grain.DAY).buckets.single().available,a.finish().buckets.single().available)
 }


 @Test fun recordedDayCountIsIndependentOfChartGrainAndSelectedApp(){
  val z=ZoneId.of("UTC");val w=Window(0,3*86400000L)
  for(g in listOf(Grain.DAY,Grain.WEEK,Grain.MONTH)){
   val a=DailyMetrics.Accumulator(w,z,g,"absent-app")
   a.addDay(listOf(MetricDayCell(0,86400000,"used",Evidence.INFERRED,1000,0),MetricDayCell(0,86400000,DailyMetrics.SUMMARY,Evidence.UNKNOWN,86400000,0)))
   a.addDay(listOf(MetricDayCell(86400000,172800000,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0),MetricDayCell(86400000,172800000,DailyMetrics.OBSERVED,Evidence.CONFIRMED,86400000,0)))
   a.addDay(listOf(MetricDayCell(172800000,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,86400000,0)))
   val result=a.finish();assertEquals(2,result.recordedDays);assertEquals(0L,result.total)
  }
 }
 @Test fun dstFragmentsCountOneCivilDayAndDoNotCountUnobservedDays(){
  val z=ZoneId.of("America/New_York");val start=Dates.midnight(LocalDate.of(2026,11,1),z)
  val end=Dates.midnight(LocalDate.of(2026,11,2),z);val w=Window(start,Dates.midnight(LocalDate.of(2026,11,3),z))
  val a=DailyMetrics.Accumulator(w,z,Grain.MONTH)
  for(f in listOf(Window(start,start+6*3600000),Window(start+6*3600000,end))){
   a.addDay(listOf(MetricDayCell(f.start,f.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0),MetricDayCell(f.start,f.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,f.duration,0)))
  }
  assertEquals(1,a.finish().recordedDays);assertEquals(86400000L,a.finish().quality!!.unknown)
 }
 @Test fun partialConfirmedCoverageOnlyMakesLegacyZeroDaysAvailable(){
  val w=Window(0,86400000);val z=ZoneId.of("UTC")
  for(legacy in listOf(false,true)){
   val a=DailyMetrics.Accumulator(w,z,Grain.MONTH,legacyCoverage=legacy)
   a.addDay(listOf(MetricDayCell(0,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,w.duration-1,0),MetricDayCell(0,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,1,0)))
   assertEquals(if(legacy)1 else 0,a.finish().recordedDays)
  }
 }
}
