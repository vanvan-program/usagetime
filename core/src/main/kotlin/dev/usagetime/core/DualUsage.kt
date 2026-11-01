package dev.usagetime.core

import java.time.ZoneId
import kotlin.math.abs

enum class UsageMetric(val label:String){PRIMARY("主要使用时间"),VISIBLE("屏幕可见时间")}
enum class Evidence{CONFIRMED,INFERRED,UNKNOWN,LEGACY}
enum class EvidenceSource{USAGE_EVENTS,OWN_WINDOW,TEST,LEGACY_IMPORT}
enum class Signal{RESUME,PAUSE,STOP,SCREEN_ON,SCREEN_OFF,NON_INTERACTIVE,LOCK,UNLOCK,SHUTDOWN,STARTUP,FOCUS_GAIN,FOCUS_LOSS,GAP,OTHER}
enum class Knowledge{YES,NO,UNKNOWN}
enum class ActivityStage{RESUMED,PAUSED,UNKNOWN}
data class ObservedEvent(val id:String,val time:Long,val signal:Signal,val app:String="",val activity:String="",val instance:String?=null,val source:EvidenceSource=EvidenceSource.USAGE_EVENTS){
    val key:String get()=listOf(source.name,app,activity,instance?:"UNRESOLVED").joinToString("|")
}
data class ActivityNode(val app:String,val activity:String,val instance:String?,val stage:ActivityStage,val source:EvidenceSource,val since:Long,val origin:String)
data class EngineState(val activities:Map<String,ActivityNode> = emptyMap(),val screen:Knowledge=Knowledge.UNKNOWN,val locked:Knowledge=Knowledge.UNKNOWN,val focuses:Map<String,String> = emptyMap(),val references:Set<String> = emptySet(),val screenRef:String?=null,val lockRef:String?=null,val focusRefs:Map<String,String> = emptyMap(),val displayOff:Boolean=false)
data class UsageSlice(val metric:UsageMetric,val app:String?,val start:Long,val end:Long,val evidence:Evidence,val reason:String,val sources:Set<EvidenceSource>,val references:Set<String>,val provisional:Boolean=false){
    init{require(start>=0&&end>start)}
    val millis:Long get()=end-start
}
data class EngineDiagnostic(val time:Long,val code:String,val detail:String)
data class ClosedEvidence(val metric:UsageMetric,val app:String,val origin:String,val at:Long,val evidence:Evidence,val focus:Boolean,val endRef:String)
data class EngineResult(val slices:List<UsageSlice>,val checkpoint:EngineState,val diagnostics:List<EngineDiagnostic>,val closures:List<ClosedEvidence> = emptyList())
data class ClockSample(val wall:Long,val elapsed:Long,val boot:String)
object ClockDiagnosis {
    fun inspect(previous:ClockSample,current:ClockSample):String?=when{
        previous.boot!=current.boot||current.elapsed<previous.elapsed->"BOOT_CHANGED"
        current.wall<previous.wall->"WALL_CLOCK_BACKWARD"
        abs((current.wall-previous.wall)-(current.elapsed-previous.elapsed))>2000->"WALL_CLOCK_DISCONTINUITY"
        else->null
    }
}
/** Public UsageEvents class names are unresolved identities, never real instance identifiers. */
object DualUsageEngine {
    const val VERSION=2
    val transitionTable:Map<Signal,String> = mapOf(
        Signal.RESUME to "set matching identity RESUMED; preserve all other identities",
        Signal.PAUSE to "matching identity PAUSED; retain visible candidate, remove focus",
        Signal.STOP to "remove matching identity and focus",
        Signal.SCREEN_ON to "screen YES; lock knowledge unchanged",
        Signal.SCREEN_OFF to "physical display OFF from direct evidence; clear focus",
        Signal.NON_INTERACTIVE to "not interactive; ambient pixels remain UNKNOWN",
        Signal.LOCK to "locked YES; clear focus",
        Signal.UNLOCK to "locked NO; screen knowledge unchanged",
        Signal.SHUTDOWN to "reset identities and focus; screen/lock UNKNOWN",
        Signal.STARTUP to "reset identities and focus; screen/lock UNKNOWN",
        Signal.FOCUS_GAIN to "accept only explicit instance and authenticated local/test source",
        Signal.FOCUS_LOSS to "remove matching focus evidence",
        Signal.GAP to "reset all knowledge to UNKNOWN",
        Signal.OTHER to "retain raw event for audit; do not infer lifecycle or focus"
    )
    fun parse(input:List<ObservedEvent>,window:Window,initial:EngineState=EngineState(),excluded:Set<String> = emptySet()):EngineResult {
        require(window.start>=0)
        var nodes=initial.activities.toMutableMap();var screen=initial.screen;var locked=initial.locked
        var focuses=initial.focuses.toMutableMap();var refs=initial.references
        var displayOff=initial.displayOff;val focusEnds=mutableMapOf<UsageMetric,MutableSet<String>>()
        var screenRef=initial.screenRef;var lockRef=initial.lockRef;val focusRefs=initial.focusRefs.toMutableMap();var boundaryRefs=emptySet<String>()
        val closures=mutableListOf<ClosedEvidence>()
        val slices=mutableListOf<UsageSlice>();val diagnostics=mutableListOf<EngineDiagnostic>()
        var at=window.start
        val events=input.filter{it.time>=window.start&&it.time<window.end&&it.signal!=Signal.OTHER}.distinctBy{it.id}.sortedWith(compareBy<ObservedEvent>{it.time}.thenBy{it.id})
        fun add(metric:UsageMetric,app:String?,end:Long,grade:Evidence,reason:String,related:Collection<ActivityNode> = emptyList()){
            if(end>at)slices+=UsageSlice(metric,app,at,end,grade,reason,related.map{it.source}.toSet(),refs+related.map{it.origin}+listOfNotNull(screenRef,lockRef)+(if(app==null)focusRefs.values else focusRefs.filter{nodes[it.key]?.app==app}.values)+boundaryRefs)
        }
        fun emit(end:Long,interrupted:Boolean=false){
            if(end<=at)return
            val knownFocus=focuses.filter{(key,pkg)->nodes[key]?.let{it.stage==ActivityStage.RESUMED&&it.app==pkg&&it.instance!=null}==true}.values.toSet()
            for(metric in UsageMetric.entries){
                when{
                    interrupted->add(metric,null,end,Evidence.UNKNOWN,"RUNTIME_BOUNDARY_WITHOUT_CLOSE")
                    displayOff||screen==Knowledge.NO&&metric==UsageMetric.PRIMARY->add(metric,null,end,Evidence.CONFIRMED,"NON_INTERACTIVE_OR_PHYSICAL_OFF")
                    screen==Knowledge.YES&&locked==Knowledge.YES&&knownFocus.size==1->{val app=knownFocus.single();add(metric,if(app in excluded)null else app,end,Evidence.CONFIRMED,"EXPLICIT_WINDOW_FOCUS_OVER_KEYGUARD",nodes.values.filter{it.app==app});if(metric==UsageMetric.VISIBLE)add(metric,null,end,Evidence.UNKNOWN,"OTHER_LOCKSCREEN_WINDOWS_UNOBSERVABLE")}
                    screen==Knowledge.NO||locked==Knowledge.YES->add(metric,null,end,Evidence.UNKNOWN,"AMBIENT_OR_KEYGUARD_VISIBILITY_UNKNOWN")
                    screen!=Knowledge.YES||locked!=Knowledge.NO->add(metric,null,end,Evidence.UNKNOWN,"SCREEN_OR_LOCK_STATE_UNKNOWN")
                    else->{
                        val candidates=nodes.values.filter{metric==UsageMetric.VISIBLE||it.stage==ActivityStage.RESUMED}
                        val uncertain=nodes.values.any{it.stage==ActivityStage.UNKNOWN}
                        val validFocus=focuses.filter{(key,pkg)->nodes[key]?.let{it.stage==ActivityStage.RESUMED&&it.app==pkg&&it.instance!=null}==true}.values.toSet()
                        if(metric==UsageMetric.PRIMARY){
                            when{
                                validFocus.size==1->{val app=validFocus.single();add(metric,if(app in excluded)null else app,end,Evidence.CONFIRMED,"EXPLICIT_WINDOW_FOCUS",nodes.values.filter{it.app==app})}
                                validFocus.size>1->add(metric,null,end,Evidence.UNKNOWN,"CONFLICTING_FOCUS")
                                uncertain->add(metric,null,end,Evidence.UNKNOWN,"AMBIGUOUS_LIFECYCLE")
                                candidates.map{it.app}.toSet().size==1->{val app=candidates.first().app;add(metric,if(app in excluded)null else app,end,Evidence.INFERRED,"SINGLE_RESUMED_PACKAGE_NOT_FOCUS_PROOF",candidates)}
                                else->add(metric,null,end,Evidence.UNKNOWN,if(candidates.isEmpty())"NO_PRIMARY_EVIDENCE"else"MULTI_RESUME_WITHOUT_FOCUS")
                            }
                        }else{
                            val visible=candidates.filter{it.stage!=ActivityStage.UNKNOWN}.groupBy{it.app}
                            if(visible.isEmpty()||uncertain||nodes.values.any{it.source!=EvidenceSource.TEST})add(metric,null,end,Evidence.UNKNOWN,"VISIBLE_SET_INCOMPLETE")
                            visible.forEach{(app,items)->if(app !in excluded)add(metric,app,end,if(validFocus.size==1&&app in validFocus)Evidence.CONFIRMED else Evidence.INFERRED,if(app in validFocus&&validFocus.size==1)"EXPLICIT_FOCUSED_WINDOW"else"LIFECYCLE_VISIBLE_CANDIDATE",items)}
                        }
                    }
                }
            }
        }
        for((time,batch) in events.groupBy{it.time}){
            val reset=batch.any{it.signal in setOf(Signal.SHUTDOWN,Signal.STARTUP,Signal.GAP)}
            boundaryRefs=batch.map{it.id}.toSet();emit(time,reset);at=time
            refs=batch.map{it.id}.toSet()
            if(reset){
                nodes.forEach{(key,node)->UsageMetric.entries.forEach{m->closures+=ClosedEvidence(m,node.app,node.origin,time,Evidence.UNKNOWN,false,batch.first().id);focusRefs[key]?.let{closures+=ClosedEvidence(m,node.app,it,time,Evidence.UNKNOWN,true,batch.first().id)}}}
                nodes.clear();focuses.clear();focusRefs.clear();screenRef=null;lockRef=null;displayOff=false;screen=Knowledge.UNKNOWN;locked=Knowledge.UNKNOWN
                diagnostics+=EngineDiagnostic(time,"STATE_RESET",batch.map{it.signal}.distinct().joinToString())
                // A same-time restart/lifecycle batch has no public total ordering. Do not guess.
                if(batch.size>1)diagnostics+=EngineDiagnostic(time,"SIMULTANEOUS_RESET","Other same-time transitions not trusted")
                continue
            }
            fun knowledge(yes:Signal,no:Signal,current:Knowledge):Knowledge{
                val hasYes=batch.any{it.signal==yes};val hasNo=batch.any{it.signal==no}
                if(hasYes&&hasNo){diagnostics+=EngineDiagnostic(time,"CONFLICTING_GLOBAL_STATE","$yes/$no");return Knowledge.UNKNOWN}
                return if(hasYes)Knowledge.YES else if(hasNo)Knowledge.NO else current
            }
            // Close only from matching observed boundaries, never from disappearance after a reset.
            val nodesBefore=nodes.toMap()
            val focusBefore=focusRefs.toMap()
            batch.groupBy{it.key}.forEach{(key,group)->
                if(group.map{it.signal}.filter{it in setOf(Signal.FOCUS_GAIN,Signal.FOCUS_LOSS)}.toSet().size<=1&&group.map{it.signal}.filter{it in setOf(Signal.RESUME,Signal.PAUSE,Signal.STOP)}.toSet().size<=1){
                    if(group.any{it.signal in setOf(Signal.FOCUS_LOSS,Signal.PAUSE,Signal.STOP)})focusBefore[key]?.let{ref->UsageMetric.entries.forEach{m->focusEnds.getOrPut(m){mutableSetOf()}.add(ref);nodesBefore[key]?.let{n->closures+=ClosedEvidence(m,n.app,ref,time,Evidence.CONFIRMED,true,group.first().id)}}}
                    nodesBefore[key]?.let{n->
                        if(group.any{it.signal==Signal.PAUSE||it.signal==Signal.STOP})closures+=ClosedEvidence(UsageMetric.PRIMARY,n.app,n.origin,time,Evidence.INFERRED,false,group.first().id)
                        if(group.any{it.signal==Signal.STOP})closures+=ClosedEvidence(UsageMetric.VISIBLE,n.app,n.origin,time,Evidence.INFERRED,false,group.first().id)
                    }
                }
            }
            if(batch.any{it.signal==Signal.SCREEN_OFF})focusBefore.values.forEach{ref->UsageMetric.entries.forEach{m->focusEnds.getOrPut(m){mutableSetOf()}.add(ref)}}
            else if(batch.any{it.signal==Signal.NON_INTERACTIVE})focusBefore.values.forEach{focusEnds.getOrPut(UsageMetric.PRIMARY){mutableSetOf()}.add(it)}
            batch.lastOrNull{it.signal in setOf(Signal.SCREEN_ON,Signal.SCREEN_OFF,Signal.NON_INTERACTIVE)}?.let{screenRef=it.id}
            batch.lastOrNull{it.signal in setOf(Signal.LOCK,Signal.UNLOCK)}?.let{lockRef=it.id}
            screen=knowledge(Signal.SCREEN_ON,Signal.SCREEN_OFF,screen)
            if(batch.any{it.signal==Signal.NON_INTERACTIVE})screen=if(batch.any{it.signal==Signal.SCREEN_ON})Knowledge.UNKNOWN else Knowledge.NO
            if(batch.any{it.signal==Signal.SCREEN_OFF})displayOff=!batch.any{it.signal==Signal.SCREEN_ON}
            else if(batch.any{it.signal in setOf(Signal.SCREEN_ON,Signal.NON_INTERACTIVE)})displayOff=false
            locked=knowledge(Signal.LOCK,Signal.UNLOCK,locked)
            if(screen==Knowledge.NO||locked==Knowledge.YES){
                // Resolve a pending prefix even when this boundary is in a later replay chunk.
                val boundary=batch.firstOrNull{it.signal in setOf(Signal.SCREEN_OFF,Signal.NON_INTERACTIVE,Signal.LOCK)}
                if(boundary!=null)focusBefore.forEach{(key,origin)->nodesBefore[key]?.let{node->
                    UsageMetric.entries.forEach{metric->
                        val grade=if(origin in focusEnds[metric].orEmpty())Evidence.CONFIRMED else Evidence.UNKNOWN
                        closures+=ClosedEvidence(metric,node.app,origin,time,grade,true,boundary.id)
                    }
                }}
                focuses.clear();focusRefs.clear()
            }
            // A public pause/stop cannot identify an own-window instance, but contradicts stale focus.
            batch.filter{it.source==EvidenceSource.USAGE_EVENTS&&it.signal in setOf(Signal.PAUSE,Signal.STOP)}.forEach{e->
                nodes.filter{(_,n)->n.source==EvidenceSource.OWN_WINDOW&&n.app==e.app&&(e.activity.isBlank()||n.activity==e.activity)}.keys.toList().forEach own@{key->
                    // The same-time own callback resolves our actual instance; the public event agrees.
                    if(batch.any{it.key==key&&it.signal in setOf(Signal.PAUSE,Signal.STOP)})return@own
                    if(batch.any{it.key==key&&it.signal==Signal.RESUME})return@own
                    val old=nodes[key]
                    if(old!=null)UsageMetric.entries.forEach{m->closures+=ClosedEvidence(m,old.app,old.origin,time,Evidence.UNKNOWN,false,e.id);focusRefs[key]?.let{closures+=ClosedEvidence(m,old.app,it,time,Evidence.UNKNOWN,true,e.id)}}
                    focuses.remove(key);focusRefs.remove(key)
                    if(e.signal==Signal.STOP)nodes.remove(key)
                    else nodes[key]?.let{nodes[key]=it.copy(stage=ActivityStage.UNKNOWN)}
                    diagnostics+=EngineDiagnostic(time,"UNRESOLVED_INSTANCE_CONTRADICTION",key)
                }
            }
            val lifecycle=batch.filter{it.signal in setOf(Signal.RESUME,Signal.PAUSE,Signal.STOP)}.groupBy{it.key}
            lifecycle.forEach{(key,group)->
                val kinds=group.map{it.signal}.toSet();val event=group.first()
                if(event.app.isBlank()){diagnostics+=EngineDiagnostic(time,"MISSING_PACKAGE",event.id);return@forEach}
                if(kinds.size>1){
                    nodes[key]=ActivityNode(event.app,event.activity,event.instance,ActivityStage.UNKNOWN,event.source,time,event.id)
                    focuses.remove(key);focusRefs.remove(key);diagnostics+=EngineDiagnostic(time,"SAME_TIME_LIFECYCLE_CONFLICT",key)
                }else when(event.signal){
                    Signal.RESUME->{
                        val existing=nodes[key]
                        if(existing?.stage==ActivityStage.RESUMED){diagnostics+=EngineDiagnostic(time,"REPEATED_RESUME_OR_INSTANCE_COLLISION",key)}
                        else {
                            if(existing?.stage==ActivityStage.PAUSED)closures+=ClosedEvidence(UsageMetric.VISIBLE,existing.app,existing.origin,time,Evidence.INFERRED,false,event.id)
                            nodes[key]=ActivityNode(event.app,event.activity,event.instance,ActivityStage.RESUMED,event.source,time,event.id)
                        }
                    }
                    Signal.PAUSE->{
                        if(key !in nodes)diagnostics+=EngineDiagnostic(time,"PAUSE_WITHOUT_RESUME",key)
                        // Missing start: candidate only from this evidence, never backfill before it.
                        val old=nodes[key];nodes[key]=ActivityNode(event.app,event.activity,event.instance,ActivityStage.PAUSED,event.source,old?.since?:time,old?.origin?:event.id)
                        focuses.remove(key);focusRefs.remove(key)
                    }
                    Signal.STOP->{if(nodes.remove(key)==null)diagnostics+=EngineDiagnostic(time,"STOP_WITHOUT_START",key);focuses.remove(key);focusRefs.remove(key)}
                    else->Unit
                }
            }
            batch.filter{it.signal==Signal.FOCUS_GAIN||it.signal==Signal.FOCUS_LOSS}.groupBy{it.key}.forEach{(key,group)->
                val e=group.first()
                if(e.instance==null||e.source !in setOf(EvidenceSource.OWN_WINDOW,EvidenceSource.TEST))diagnostics+=EngineDiagnostic(time,"UNTRUSTED_FOCUS_REJECTED",key)
                else if(group.map{it.signal}.toSet().size>1){focuses.remove(key);focusRefs.remove(key);diagnostics+=EngineDiagnostic(time,"CONFLICTING_FOCUS_TRANSITION",key)}
                else if(e.signal==Signal.FOCUS_GAIN){if(key !in focuses){focuses[key]=e.app;focusRefs[key]=e.id}}else {focuses.remove(key);focusRefs.remove(key)}
            }
        }
        boundaryRefs=emptySet();emit(window.end)
        val tail=EngineState(nodes.toMap(),screen,locked,focuses.toMap(),refs,screenRef,lockRef,focusRefs.toMap(),displayOff)
        val resolvedClosures=normalizeClosures(closures)
        val confirmedFocus=resolvedClosures.filter{it.focus&&it.evidence==Evidence.CONFIRMED}.groupBy{it.metric}.mapValues{(_,v)->v.map{it.origin}.toSet()}
        val abortedRefs=resolvedClosures.asSequence().filter{it.evidence==Evidence.UNKNOWN}
            .groupBy{it.metric to it.app}.mapValues{(_,items)->items.map{it.origin}.toHashSet()}
        val annotated=slices.map{slice->
            val live=nodes.values.filter{it.app==slice.app&&(slice.metric==UsageMetric.VISIBLE||it.stage==ActivityStage.RESUMED)}
            val explicit=slice.reason.startsWith("EXPLICIT")
            val closed=explicit&&slice.references.any{it in confirmedFocus[slice.metric].orEmpty()}
            val activeProof=explicit&&slice.references.any{it in focusRefs.values}
            val pending=if(slice.app==null)slice.end==window.end else if(explicit)!closed&&activeProof else live.any{slice.end>it.since}
            val aborted=slice.references.any{it in abortedRefs[slice.metric to slice.app].orEmpty()}
            val grade=when{aborted&&!(explicit&&closed)->Evidence.UNKNOWN;explicit&&!closed&&!activeProof->Evidence.UNKNOWN;pending&&slice.evidence==Evidence.CONFIRMED->Evidence.INFERRED;else->slice.evidence}
            slice.copy(provisional=pending,evidence=grade,reason=slice.reason+if(explicit&&!closed&&!activeProof)"_END_UNKNOWN"else if(pending&&slice.evidence==Evidence.CONFIRMED)"_OPEN_TAIL"else "")
        }
        return EngineResult(merge(annotated),tail,diagnostics,resolvedClosures)
    }
    fun normalizeClosures(values:List<ClosedEvidence>):List<ClosedEvidence> = values
        .groupBy{listOf(it.metric,it.app,it.origin,it.at,it.focus)}.values.map{group->
            val chosen=group.maxBy{when(it.evidence){Evidence.UNKNOWN->3;Evidence.LEGACY->2;Evidence.INFERRED->1;Evidence.CONFIRMED->0}}
            chosen.copy(endRef=group.flatMap{it.endRef.split(',')}.distinct().sorted().joinToString(","))
        }
    fun merge(input:List<UsageSlice>):List<UsageSlice> = input.groupBy{listOf(it.metric,it.app,it.evidence,it.reason,it.provisional,it.sources,it.references)}.values.flatMap{group->
        val result=mutableListOf<UsageSlice>()
        group.sortedBy{it.start}.forEach{item->val last=result.lastOrNull();if(last!=null&&item.start<=last.end)result[result.lastIndex]=last.copy(end=maxOf(last.end,item.end))else result+=item};result
    }.sortedWith(compareBy<UsageSlice>{it.start}.thenBy{it.metric.name}.thenBy{it.app?:""})
}
data class MetricSummary(val confirmed:Long,val inferred:Long,val unknown:Long,val provisional:Long)
object DualReports {
    fun summary(slices:List<UsageSlice>,metric:UsageMetric,window:Window,app:String?=null):MetricSummary{
        val scope=slices.filter{it.metric==metric}
        val chosen=scope.filter{app==null||it.app==app}
        fun duration(grade:Evidence)=chosen.filter{it.evidence==grade&&it.app!=null}.groupBy{it.app}.values.sumOf{items->Reports.unionDuration(items.map{Window(it.start,it.end)},window)}
        fun uncertain(s:UsageSlice)=s.evidence==Evidence.UNKNOWN||s.app==null&&s.evidence==Evidence.INFERRED
        val known=scope.filter{!uncertain(it)}.map{Window(it.start,it.end)}
        val explicitUnknown=Reports.unionDuration(scope.filter{uncertain(it)}.map{Window(it.start,it.end)},window)
        val missing=(window.duration-Reports.unionDuration(known+scope.filter{uncertain(it)}.map{Window(it.start,it.end)},window)).coerceAtLeast(0)
        return MetricSummary(duration(Evidence.CONFIRMED),duration(Evidence.INFERRED),explicitUnknown+missing,Reports.unionDuration(chosen.filter{it.provisional}.map{Window(it.start,it.end)},window))
    }
    fun daily(slices:List<UsageSlice>,metric:UsageMetric,window:Window,zone:ZoneId):List<Pair<Window,MetricSummary>> = Reports.slots(window,zone,Grain.DAY).map{it to summary(slices,metric,it)}
}
