package dev.usagetime.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MetricDayCell(val start:Long,val end:Long,val app:String?,val evidence:Evidence,val millis:Long,val provisional:Long)
object DailyMetrics {
    const val SUMMARY="__QUALITY_SUMMARY__"
    const val OBSERVED="__CONFIRMED_COVERAGE__"
    /** Filter only attributed values; quality and coverage sentinels describe the original evidence. */
    fun displayCells(cells:List<MetricDayCell>,includeConfirmed:Boolean):List<MetricDayCell> =
        if(includeConfirmed)cells else cells.filter{it.evidence!=Evidence.CONFIRMED||it.app==null||it.app==SUMMARY||it.app==OBSERVED}
    fun cells(slices:List<UsageSlice>,metric:UsageMetric,window:Window):List<MetricDayCell>{
        val data=slices.filter{it.metric==metric&&it.end>window.start&&it.start<window.end}
        val summary=DualReports.summary(data,metric,window)
        val grouped=data.groupBy{it.app to it.evidence}.map{(key,items)->MetricDayCell(window.start,window.end,key.first,key.second,Reports.unionDuration(items.map{Window(it.start,it.end)},window),Reports.unionDuration(items.filter{it.provisional}.map{Window(it.start,it.end)},window))}
        return grouped+MetricDayCell(window.start,window.end,SUMMARY,Evidence.UNKNOWN,summary.unknown,summary.provisional)+MetricDayCell(window.start,window.end,OBSERVED,Evidence.CONFIRMED,Reports.unionDuration(data.filter{it.evidence==Evidence.CONFIRMED&&!it.provisional}.map{Window(it.start,it.end)},window),0)
    }
    /** Sums disjoint bucket cells into one cell set covering [slot]; measures are additive across disjoint windows. */
    fun mergeCells(parts:List<List<MetricDayCell>>,slot:Window):List<MetricDayCell>{
        val totals=linkedMapOf<Pair<String?,Evidence>,LongArray>()
        parts.forEach{list->list.forEach{cell->
            val t=totals.getOrPut(cell.app to cell.evidence){LongArray(2)}
            t[0]+=cell.millis;t[1]+=cell.provisional
        }}
        return totals.map{(key,t)->MetricDayCell(slot.start,slot.end,key.first,key.second,t[0],t[1])}
    }
    /** Non-legacy availability of one fully covered day-slot cell set, matching Accumulator semantics. */
    fun recordedDays(window:Window,cells:List<MetricDayCell>,zone:ZoneId):Int{
        val days=AvailableDays(window,zone)
        cells.groupBy{it.start}.toSortedMap().values.forEach{days.add(it)}
        return days.count()
    }
    /** Hour-grain report built from per-hour stored cells; equals the slice-based build when rows are complete. */
    fun hourReport(slotCells:List<Pair<Window,List<MetricDayCell>>>,window:Window,zone:ZoneId,app:String?):UsageReport{
        val attributed=slotCells.flatMap{it.second}.filter{attributedCells(it)}
        val totals=attributed.filter{app==null||it.app==app}.groupBy{it.app!!}.map{(pkg,items)->AppTotal(pkg,items.sumOf{it.millis})}.sortedByDescending{it.millis}
        var covered=0L
        val buckets=slotCells.map{(slot,cells)->
            val value=cells.filter{attributedCells(it)&&(app==null||it.app==app)}.sumOf{it.millis}
            val observed=cells.filter{it.app==OBSERVED}.sumOf{it.millis}
            covered+=observed
            val complete=observed>=slot.duration
            val label=Instant.ofEpochMilli(slot.start).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
            Bucket(slot.start,slot.end,label,value,complete,complete||value>0||observed>0)
        }
        val summary=slotCells.flatMap{it.second}.filter{it.app==SUMMARY}
        val unknown=summary.sumOf{it.millis};val provisional=summary.sumOf{it.provisional}
        fun quality(pkg:String?):MetricSummary{
            val rows=attributed.filter{pkg==null||it.app==pkg}
            val confirmed=rows.filter{it.evidence==Evidence.CONFIRMED}.sumOf{it.millis}
            val inferred=rows.filter{it.evidence==Evidence.INFERRED}.sumOf{it.millis}
            // Unknown spans are global in both the slice-based summary and the cell cache.
            return MetricSummary(confirmed,inferred,unknown,if(pkg==null)provisional else rows.sumOf{it.provisional})
        }
        return UsageReport(window,buckets,totals,covered,window.duration>0&&covered>=window.duration,quality(app),totals.associate{it.app to quality(it.app)})
    }
    /** Month-grain report built from per-month cells merged out of day cells; mirrors Accumulator.finish. */
    fun monthReport(slotCells:List<Pair<Window,List<MetricDayCell>>>,window:Window,zone:ZoneId,app:String?,recordedDays:Long):UsageReport{
        val attributed=slotCells.flatMap{it.second}.filter{attributedCells(it)}
        val totals=attributed.filter{app==null||it.app==app}.groupBy{it.app!!}.map{(pkg,items)->AppTotal(pkg,items.sumOf{it.millis})}.sortedByDescending{it.millis}
        var covered=0L;var unknown=0L;var provisional=0L
        val buckets=slotCells.map{(slot,cells)->
            val value=cells.filter{attributedCells(it)&&(app==null||it.app==app)}.sumOf{it.millis}
            val observed=cells.filter{it.app==OBSERVED}.sumOf{it.millis}
            val missing=cells.filter{it.app==SUMMARY}.sumOf{it.millis}
            unknown+=missing;provisional+=cells.filter{it.app==SUMMARY}.sumOf{it.provisional}
            covered+=observed
            val complete=observed>=slot.duration&&missing==0L
            val date=Dates.date(slot.start,zone)
            Bucket(slot.start,slot.end,"${date.year}年${date.monthValue}月",value,complete,value>0||complete)
        }
        fun quality(pkg:String?):MetricSummary{
            val rows=attributed.filter{pkg==null||it.app==pkg}
            return MetricSummary(rows.filter{it.evidence==Evidence.CONFIRMED}.sumOf{it.millis},rows.filter{it.evidence==Evidence.INFERRED}.sumOf{it.millis},unknown,if(pkg==null)provisional else rows.sumOf{it.provisional})
        }
        val q=quality(app)
        return UsageReport(window,buckets,totals,covered,window.duration>0&&covered>=window.duration&&q.unknown==0L&&q.inferred==0L&&q.provisional==0L,q,totals.associate{it.app to quality(it.app)},recordedDays.toInt())
    }
    private fun attributedCells(cell:MetricDayCell)=cell.app!=null&&cell.app!=SUMMARY&&cell.app!=OBSERVED&&cell.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)
    /** Counts civil days while keeping only the current day's fragment totals. */
    private class AvailableDays(private val window:Window,private val zone:ZoneId,private val legacy:Boolean=false){
        private var day:Window?=null;private var visited=0L;private var observed=0L;private var unknown=0L
        private var positive=false;private var completed=0
        private fun available()=positive||(day!=null&&visited>=day!!.duration&&observed>=day!!.duration&&unknown==0L)||legacy&&observed>0
        fun add(items:List<MetricDayCell>){
            val first=items.firstOrNull()?:return
            val date=Dates.date(first.start,zone)
            val w=Window(maxOf(window.start,Dates.midnight(date,zone)),minOf(window.end,Dates.midnight(date.plusDays(1),zone)))
            require(first.end<=w.end)
            if(day!=w){if(available())completed++;day=w;visited=0;observed=0;unknown=0;positive=false}
            visited+=first.end-first.start
            items.forEach{when(it.app){
                SUMMARY->unknown+=it.millis
                OBSERVED->observed+=it.millis
                null->Unit
                else->if(it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)&&it.millis>0)positive=true
            }}
        }
        fun count()=completed+if(available())1 else 0
    }
    /** Retains bucket/app totals only, not ten years of daily rows. Feed disjoint days in order. */
    class Accumulator(private val window:Window,private val zone:ZoneId,private val grain:Grain,private val app:String?=null,private val legacyCoverage:Boolean=false){
        private val slots=Reports.slots(window,zone,grain)
        private val availableDays=AvailableDays(window,zone,legacyCoverage)
        private val values=LongArray(slots.size);private val unknown=LongArray(slots.size)
        private val observed=LongArray(slots.size);private val visited=LongArray(slots.size)
        private val apps=linkedMapOf<String,LongArray>()
        private var lastEnd=window.start;private var provisional=0L
        init{require(grain!=Grain.HOUR)}
        fun addDay(items:List<MetricDayCell>){
            if(items.isEmpty())return
            val first=items.first();require(first.start>=lastEnd&&first.end<=window.end&&first.end>first.start)
            require(items.all{it.start==first.start&&it.end==first.end})
            availableDays.add(items);lastEnd=first.end
            val i=slots.binarySearch{if(it.end<=first.start)-1 else if(it.start>first.start)1 else 0}
            require(i>=0&&first.end<=slots[i].end)
            visited[i]+=first.end-first.start
            items.forEach{cell->when(cell.app){
                SUMMARY->{unknown[i]+=cell.millis;provisional+=cell.provisional}
                OBSERVED->observed[i]+=cell.millis
                null->Unit
                else->if(cell.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)){
                    val totals=apps.getOrPut(cell.app){LongArray(3)}
                    totals[if(cell.evidence==Evidence.CONFIRMED)0 else 1]+=cell.millis;totals[2]+=cell.provisional
                    if(app==null||app==cell.app)values[i]+=cell.millis
                }
            }}
        }
        fun finish():UsageReport{
            val missing=slots.indices.sumOf{(slots[it].duration-visited[it]).coerceAtLeast(0)}
            val rangeUnknown=unknown.sum()+missing
            fun quality(pkg:String?):MetricSummary{
                val rows=if(pkg==null)apps.values.toList()else listOfNotNull(apps[pkg])
                return MetricSummary(rows.sumOf{it[0]},rows.sumOf{it[1]},rangeUnknown,if(pkg==null)provisional else rows.sumOf{it[2]})
            }
            val totals=apps.filterKeys{app==null||it==app}.map{(pkg,t)->AppTotal(pkg,t[0]+t[1])}.sortedByDescending{it.millis}
            val buckets=slots.mapIndexed{i,w->
                val date=Dates.date(w.start,zone)
                val complete=visited[i]>=w.duration&&observed[i]>=w.duration&&unknown[i]==0L
                Bucket(w.start,w.end,if(grain==Grain.MONTH)"${date.year}年${date.monthValue}月"else"${date.monthValue}/${date.dayOfMonth}",values[i],complete,values[i]>0||complete||legacyCoverage&&observed[i]>0)
            }
            val q=quality(app);val covered=observed.sum()
            return UsageReport(window,buckets,totals,covered,window.duration>0&&covered>=window.duration&&q.unknown==0L&&q.inferred==0L&&q.provisional==0L,q,totals.associate{it.app to quality(it.app)},availableDays.count())
        }
    }
    /** Every input cell describes an entire day (or exact boundary-day fragment), never a fabricated timestamp interval. */
    fun report(cells:List<MetricDayCell>,window:Window,zone:ZoneId,grain:Grain,app:String?=null):UsageReport{
        require(cells.all{it.start>=window.start&&it.end<=window.end})
        val days=cells.groupBy{it.start}
        val availableDays=AvailableDays(window,zone)
        days.toSortedMap().values.forEach{availableDays.add(it)}
        val attributed=cells.filter{it.app!=null&&it.app !in setOf(SUMMARY,OBSERVED)&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)}
        val selected=attributed.filter{app==null||it.app==app}
        val totals=selected.groupBy{it.app!!}.map{(pkg,items)->AppTotal(pkg,items.sumOf{it.millis})}.sortedByDescending{it.millis}
        fun quality(pkg:String?):MetricSummary{
            val items=attributed.filter{pkg==null||it.app==pkg}
            val unknown=cells.filter{it.app==SUMMARY}.sumOf{it.millis}
            return MetricSummary(items.filter{it.evidence==Evidence.CONFIRMED}.sumOf{it.millis},items.filter{it.evidence==Evidence.INFERRED}.sumOf{it.millis},unknown,if(pkg==null)cells.filter{it.app==SUMMARY}.sumOf{it.provisional}else items.sumOf{it.provisional})
        }
        val slots=Reports.slots(window,zone,grain);val values=LongArray(slots.size);val missing=LongArray(slots.size);val observed=LongArray(slots.size)
        // A day cell may only be assigned to a containing bucket. HOUR is handled by raw slices instead.
        require(grain!=Grain.HOUR)
        days.forEach{(start,items)->
            val i=slots.binarySearch{if(it.end<=start)-1 else if(it.start>start)1 else 0}
            if(i>=0){require(items.all{it.end<=slots[i].end});values[i]+=items.filter{it.app!=null&&it.app !in setOf(SUMMARY,OBSERVED)&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)&&(app==null||it.app==app)}.sumOf{it.millis};missing[i]+=items.filter{it.app==SUMMARY}.sumOf{it.millis};observed[i]+=items.filter{it.app==OBSERVED}.sumOf{it.millis}}
        }
        val buckets=slots.mapIndexed{i,w->val date=Dates.date(w.start,zone);val label=if(grain==Grain.MONTH)"${date.year}年${date.monthValue}月"else"${date.monthValue}/${date.dayOfMonth}";Bucket(w.start,w.end,label,values[i],observed[i]>=w.duration&&missing[i]==0L,values[i]>0||observed[i]>=w.duration&&missing[i]==0L)}
        val q=quality(app);val covered=cells.filter{it.app==OBSERVED}.sumOf{it.millis}
        return UsageReport(window,buckets,totals,covered,window.duration>0&&covered>=window.duration&&q.unknown==0L&&q.inferred==0L&&q.provisional==0L,q,totals.associate{it.app to quality(it.app)},availableDays.count())
    }
}
