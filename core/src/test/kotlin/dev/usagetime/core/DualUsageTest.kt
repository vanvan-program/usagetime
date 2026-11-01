package dev.usagetime.core

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class DualUsageTest {
    private fun e(t:Long,s:Signal,app:String="a",instance:String?="one",source:EvidenceSource=EvidenceSource.TEST,activity:String="A")=ObservedEvent("$t/$s/$app/$instance/$source/$activity",t,s,app,activity,instance,source)
    private val start=listOf(e(0,Signal.SCREEN_ON),e(0,Signal.UNLOCK))
    private fun parse(events:List<ObservedEvent>,end:Long=100)=DualUsageEngine.parse(start+events,Window(0,end))
    private fun total(r:EngineResult,m:UsageMetric,g:Evidence?=null,app:String?="a")=r.slices.filter{it.metric==m&&it.app==app&&(g==null||it.evidence==g)}.sumOf{it.millis}
    @Test fun singleResumeIsInferredNotConfirmed(){val r=parse(listOf(e(10,Signal.RESUME),e(40,Signal.STOP)));assertEquals(30L,total(r,UsageMetric.PRIMARY,Evidence.INFERRED));assertEquals(0L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED))}
    @Test fun pausedActivityRemainsVisibleCandidate(){val r=parse(listOf(e(10,Signal.RESUME),e(30,Signal.PAUSE),e(60,Signal.STOP)));assertEquals(20L,total(r,UsageMetric.PRIMARY));assertEquals(50L,total(r,UsageMetric.VISIBLE))}
    @Test fun multiResumeDoesNotSelectLastApp(){val r=parse(listOf(e(0,Signal.RESUME),e(20,Signal.RESUME,"b"),e(60,Signal.STOP,"b"),e(80,Signal.STOP)));assertEquals(40L,total(r,UsageMetric.PRIMARY));assertEquals(0L,total(r,UsageMetric.PRIMARY,app="b"));assertTrue(r.slices.any{it.reason=="MULTI_RESUME_WITHOUT_FOCUS"&&it.start==20L&&it.end==60L})}
    @Test fun splitScreenVisibleCanOverlap(){val r=parse(listOf(e(0,Signal.RESUME),e(0,Signal.RESUME,"b"),e(70,Signal.STOP),e(80,Signal.STOP,"b")));assertEquals(70L,total(r,UsageMetric.VISIBLE));assertEquals(80L,total(r,UsageMetric.VISIBLE,app="b"))}
    @Test fun activityInstancesOfSameClassAreIndependent(){val r=parse(listOf(e(0,Signal.RESUME,instance="1"),e(10,Signal.RESUME,instance="2"),e(20,Signal.STOP,instance="1"),e(40,Signal.STOP,instance="2")));assertEquals(40L,total(r,UsageMetric.VISIBLE));assertEquals(40L,total(r,UsageMetric.PRIMARY))}
    @Test fun completeExplicitFocusIsConfirmed(){val r=parse(listOf(e(10,Signal.RESUME),e(10,Signal.FOCUS_GAIN),e(50,Signal.FOCUS_LOSS),e(50,Signal.STOP)));assertEquals(40L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED));assertEquals(40L,total(r,UsageMetric.VISIBLE,Evidence.CONFIRMED))}
    @Test fun publicFocusCannotBeInvented(){val r=parse(listOf(e(0,Signal.RESUME,source=EvidenceSource.USAGE_EVENTS),e(0,Signal.FOCUS_GAIN,source=EvidenceSource.USAGE_EVENTS),e(40,Signal.STOP,source=EvidenceSource.USAGE_EVENTS)));assertEquals(0L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED));assertTrue(r.diagnostics.any{it.code=="UNTRUSTED_FOCUS_REJECTED"})}
    @Test fun unknownScreenAndLockNeverImplyUnlocked(){val r=DualUsageEngine.parse(listOf(e(0,Signal.RESUME)),Window(0,100));assertEquals(0L,total(r,UsageMetric.PRIMARY));assertEquals(100L,DualReports.summary(r.slices,UsageMetric.PRIMARY,Window(0,100)).unknown)}
    @Test fun screenOnDoesNotUnlock(){val r=parse(listOf(e(10,Signal.RESUME),e(20,Signal.LOCK),e(30,Signal.SCREEN_ON),e(50,Signal.UNLOCK),e(70,Signal.STOP)));assertEquals(30L,total(r,UsageMetric.PRIMARY))}
    @Test fun screenOffSuspendsBothMetrics(){val r=parse(listOf(e(0,Signal.RESUME),e(20,Signal.SCREEN_OFF),e(50,Signal.SCREEN_ON),e(70,Signal.STOP)));assertEquals(40L,total(r,UsageMetric.PRIMARY));assertEquals(40L,total(r,UsageMetric.VISIBLE))}
    @Test fun noTouchTimeoutForReadingOrVideo(){val r=parse(listOf(e(0,Signal.RESUME),e(9999999,Signal.STOP)),10000000);assertEquals(9999999L,total(r,UsageMetric.PRIMARY))}
    @Test fun simultaneousLifecycleOrderIsInvariant(){val first=e(0,Signal.RESUME);val pause=e(10,Signal.PAUSE);val resume=e(10,Signal.RESUME);val stop=e(30,Signal.STOP);val a=parse(listOf(first,pause,resume,stop));val b=parse(listOf(stop,resume,first,pause));assertEquals(a,b);assertTrue(a.diagnostics.any{it.code=="SAME_TIME_LIFECYCLE_CONFLICT"})}
    @Test fun duplicatedEventsAreIdempotent(){val events=listOf(e(0,Signal.RESUME),e(25,Signal.STOP));assertEquals(parse(events),parse(events+events))}
    @Test fun missingStartNotBackfilled(){val r=parse(listOf(e(50,Signal.PAUSE),e(70,Signal.STOP)));assertEquals(20L,total(r,UsageMetric.VISIBLE));assertEquals(0L,total(r,UsageMetric.PRIMARY));assertTrue(r.diagnostics.any{it.code=="PAUSE_WITHOUT_RESUME"})}
    @Test fun openTailIsProvisionalAndNotConfirmed(){val r=parse(listOf(e(10,Signal.RESUME),e(10,Signal.FOCUS_GAIN)));assertEquals(0L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED));assertTrue(r.slices.any{it.app=="a"&&it.provisional})}
    @Test fun checkpointCarriesMultipleActivities(){val a=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(5,Signal.RESUME,"b")),Window(0,10));assertEquals(2,a.checkpoint.activities.size);val b=DualUsageEngine.parse(listOf(e(20,Signal.STOP,"b"),e(30,Signal.STOP)),Window(10,40),a.checkpoint);assertEquals(10L,total(b,UsageMetric.PRIMARY));assertEquals(20L,total(b,UsageMetric.VISIBLE))}
    @Test fun restartDoesNotExtendOldOwner(){val r=parse(listOf(e(0,Signal.RESUME),e(20,Signal.STARTUP),e(70,Signal.RESUME,"b")));assertEquals(0L,total(r,UsageMetric.PRIMARY));assertTrue(r.diagnostics.any{it.code=="STATE_RESET"})}
    @Test fun gapResetsKnowledge(){val r=parse(listOf(e(0,Signal.RESUME),e(20,Signal.GAP),e(50,Signal.RESUME)));assertTrue(r.slices.any{it.evidence==Evidence.UNKNOWN&&it.end==100L})}
    @Test fun conflictingFocusIsUnknown(){val r=parse(listOf(e(0,Signal.RESUME),e(0,Signal.RESUME,"b"),e(5,Signal.FOCUS_GAIN),e(5,Signal.FOCUS_GAIN,"b")));assertTrue(r.slices.any{it.reason=="CONFLICTING_FOCUS"})}
    @Test fun repeatedResumeDoesNotResetSession(){val r=parse(listOf(e(0,Signal.RESUME),e(20,Signal.RESUME),e(40,Signal.STOP)));assertEquals(40L,total(r,UsageMetric.VISIBLE));assertTrue(r.diagnostics.any{it.code=="REPEATED_RESUME_OR_INSTANCE_COLLISION"})}
    @Test fun appUnionDoesNotDoubleCountInstances(){val r=parse(listOf(e(0,Signal.RESUME,instance="1"),e(0,Signal.RESUME,instance="2"),e(40,Signal.STOP,instance="1"),e(40,Signal.STOP,instance="2")));assertEquals(40L,DualReports.summary(r.slices,UsageMetric.VISIBLE,Window(0,40)).inferred)}
    @Test fun halfOpenBoundaryDefersEventAtEnd(){val a=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(20,Signal.STOP)),Window(0,20));assertEquals(1,a.checkpoint.activities.size);val b=DualUsageEngine.parse(listOf(e(20,Signal.STOP)),Window(20,30),a.checkpoint);assertTrue(b.checkpoint.activities.isEmpty());assertEquals(0L,total(b,UsageMetric.VISIBLE))}
    @Test fun unknownAndUnobservedTimeDoNotBecomeZero(){val r=DualReports.summary(listOf(UsageSlice(UsageMetric.PRIMARY,null,0,10,Evidence.UNKNOWN,"gap",emptySet(),emptySet())),UsageMetric.PRIMARY,Window(0,30));assertEquals(30L,r.unknown)}
    @Test fun crossDayAndDstAreExact(){val z=ZoneId.of("America/New_York");val d=LocalDate.of(2026,3,8);val w=Window(Dates.midnight(d,z),Dates.midnight(d.plusDays(1),z));val s=UsageSlice(UsageMetric.PRIMARY,"a",w.start,w.end,Evidence.INFERRED,"test",emptySet(),emptySet());assertEquals(23*3600000L,DualReports.daily(listOf(s),UsageMetric.PRIMARY,w,z).single().second.inferred)}
    @Test fun timezoneChangesOnlyBucketAssignment(){val w=Window(0,86400000);val s=UsageSlice(UsageMetric.VISIBLE,"a",0,86400000,Evidence.INFERRED,"test",emptySet(),emptySet());val a=DualReports.daily(listOf(s),UsageMetric.VISIBLE,w,ZoneId.of("UTC"));val b=DualReports.daily(listOf(s),UsageMetric.VISIBLE,w,ZoneId.of("Asia/Shanghai"));assertEquals(a.sumOf{it.second.inferred},b.sumOf{it.second.inferred})}
    @Test fun clockChangesAreDiagnosedNotCorrected(){val old=ClockSample(100000,1000,"b");assertEquals("WALL_CLOCK_BACKWARD",ClockDiagnosis.inspect(old,ClockSample(90000,2000,"b")));assertEquals("WALL_CLOCK_DISCONTINUITY",ClockDiagnosis.inspect(old,ClockSample(200000,2000,"b")));assertEquals("BOOT_CHANGED",ClockDiagnosis.inspect(old,ClockSample(101000,2000,"c")));assertNull(ClockDiagnosis.inspect(old,ClockSample(101000,2000,"b")))}
    @Test fun transitionTableCoversAllSignals(){assertEquals(Signal.entries.toSet(),DualUsageEngine.transitionTable.keys)}

    @Test fun primaryConflictInImportedHistoryBecomesUnknown(){
        val a=UsageSlice(UsageMetric.PRIMARY,"a",0,20,Evidence.INFERRED,"archive",emptySet(),emptySet())
        val b=a.copy(app="b",start=10,end=30)
        val resolved=DualIntervals.resolvePrimary(listOf(a,b))
        assertEquals(10L,resolved.filter{it.evidence==Evidence.UNKNOWN}.sumOf{it.millis})
        assertEquals(20L,resolved.filter{it.app!=null}.sumOf{it.millis})
    }
    @Test fun focusAndScreenProofReferencesPersistAcrossCheckpoint(){
        val first=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(0,Signal.FOCUS_GAIN)),Window(0,10))
        val second=DualUsageEngine.parse(listOf(e(20,Signal.FOCUS_LOSS),e(20,Signal.STOP)),Window(10,30),first.checkpoint)
        val confirmed=second.slices.first{it.evidence==Evidence.CONFIRMED&&it.app=="a"}
        assertTrue(confirmed.references.contains(e(0,Signal.FOCUS_GAIN).id));assertTrue(confirmed.references.contains(e(0,Signal.SCREEN_ON).id));assertTrue(confirmed.references.contains(e(20,Signal.FOCUS_LOSS).id))
    }
    @Test fun tenYearDailyAggregationPreservesLongMilliseconds(){
        val z=ZoneId.of("UTC");val start=LocalDate.of(2016,1,1);val end=start.plusYears(10)
        val cells=mutableListOf<MetricDayCell>();var d=start
        while(d<end){val a=Dates.midnight(d,z);val b=Dates.midnight(d.plusDays(1),z);cells+=MetricDayCell(a,b,"a",Evidence.INFERRED,600001L,0);cells+=MetricDayCell(a,b,DailyMetrics.SUMMARY,Evidence.UNKNOWN,b-a-600001,0);d=d.plusDays(1)}
        val w=Window(Dates.midnight(start,z),Dates.midnight(end,z));val report=DailyMetrics.report(cells,w,z,Grain.MONTH)
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(start,end)*600001L,report.total);assertEquals(120,report.buckets.size);assertEquals(report.total,report.buckets.sumOf{it.millis})
    }
    @Test fun cachedConfirmedIdleIsZeroButUnknownIsUnavailable(){
        val z=ZoneId.of("UTC");val w=Window(0,86400000)
        val idle=listOf(UsageSlice(UsageMetric.PRIMARY,null,0,w.end,Evidence.CONFIRMED,"locked",emptySet(),emptySet()))
        val a=DailyMetrics.report(DailyMetrics.cells(idle,UsageMetric.PRIMARY,w),w,z,Grain.DAY)
        val b=DailyMetrics.report(DailyMetrics.cells(emptyList(),UsageMetric.PRIMARY,w),w,z,Grain.DAY)
        assertTrue(a.buckets.single().available);assertEquals(0L,a.total);assertFalse(b.buckets.single().available);assertEquals(w.duration,b.quality!!.unknown)
    }
    @Test fun productionVisibleCandidatesNeverProveExhaustiveWindowSet(){
        val r=parse(listOf(e(0,Signal.RESUME,instance=null,source=EvidenceSource.USAGE_EVENTS),e(30,Signal.STOP,instance=null,source=EvidenceSource.USAGE_EVENTS)))
        assertEquals(30L,total(r,UsageMetric.VISIBLE,Evidence.INFERRED));assertTrue(r.slices.any{it.metric==UsageMetric.VISIBLE&&it.evidence==Evidence.UNKNOWN})
    }

    @Test fun ambientDisplayNeverMeansConfirmedInvisible(){val r=parse(listOf(e(0,Signal.RESUME),e(10,Signal.NON_INTERACTIVE),e(50,Signal.SCREEN_ON),e(70,Signal.STOP)));assertTrue(r.slices.any{it.metric==UsageMetric.VISIBLE&&it.start==10L&&it.end==50L&&it.evidence==Evidence.UNKNOWN})}
    @Test fun lostFocusAtGapDoesNotFinalizeConfirmation(){val r=parse(listOf(e(0,Signal.RESUME),e(0,Signal.FOCUS_GAIN),e(20,Signal.RESUME,"b"),e(30,Signal.GAP)));assertEquals(0L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED));assertTrue(r.slices.any{it.reason.endsWith("END_UNKNOWN")})}
    @Test fun keyguardWithoutWindowEvidenceIsUnknownNotInvisible(){val r=parse(listOf(e(0,Signal.RESUME),e(10,Signal.LOCK),e(60,Signal.UNLOCK),e(80,Signal.STOP)));assertTrue(r.slices.any{it.metric==UsageMetric.VISIBLE&&it.start==10L&&it.end==60L&&it.evidence==Evidence.UNKNOWN})}

    @Test fun contradictoryIdleAndAppImportsAreOrderIndependentUnknown(){
        val idle=UsageSlice(UsageMetric.PRIMARY,null,0,100,Evidence.INFERRED,"import",setOf(EvidenceSource.LEGACY_IMPORT),emptySet())
        val app=idle.copy(app="a")
        val a=DualIntervals.resolvePrimary(listOf(idle,app));val b=DualIntervals.resolvePrimary(listOf(app,idle))
        assertEquals(a,b);assertEquals(100L,a.filter{it.evidence==Evidence.UNKNOWN}.sumOf{it.millis})
    }
    @Test fun retentionGapBeforeLaterCloseCannotExtendOldSession(){
        val first=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME)),Window(0,10))
        val later=DualUsageEngine.parse(listOf(e(10,Signal.GAP),e(1000,Signal.STOP)),Window(10,1010),first.checkpoint)
        assertEquals(0L,total(later,UsageMetric.PRIMARY));assertTrue(later.slices.any{it.evidence==Evidence.UNKNOWN})
    }
    @Test fun laterLockClosesPendingFocusAsUnknownAcrossCheckpoint(){
        val first=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(0,Signal.FOCUS_GAIN)),Window(0,10))
        val second=DualUsageEngine.parse(listOf(e(20,Signal.LOCK)),Window(10,30),first.checkpoint)
        assertEquals(2,second.closures.count{it.focus&&it.origin==e(0,Signal.FOCUS_GAIN).id&&it.evidence==Evidence.UNKNOWN})
        assertEquals(0L,total(second,UsageMetric.PRIMARY,Evidence.CONFIRMED))
    }
    @Test fun laterNoninteractiveSeparatesPrimaryClosureAndAmbientUncertainty(){
        val first=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(0,Signal.FOCUS_GAIN)),Window(0,10))
        val second=DualUsageEngine.parse(listOf(e(20,Signal.NON_INTERACTIVE)),Window(10,30),first.checkpoint)
        assertTrue(second.closures.any{it.focus&&it.metric==UsageMetric.PRIMARY&&it.evidence==Evidence.CONFIRMED})
        assertTrue(second.closures.any{it.focus&&it.metric==UsageMetric.VISIBLE&&it.evidence==Evidence.UNKNOWN})
    }

    @Test fun resumeAfterPauseFinalizesPreviousVisibleOriginAcrossChunks(){
        val first=DualUsageEngine.parse(start+listOf(e(0,Signal.RESUME),e(5,Signal.PAUSE)),Window(0,10))
        val next=DualUsageEngine.parse(listOf(e(15,Signal.RESUME),e(25,Signal.STOP)),Window(10,30),first.checkpoint)
        assertTrue(next.closures.any{it.metric==UsageMetric.VISIBLE&&it.origin==e(0,Signal.RESUME).id&&it.at==15L&&it.evidence==Evidence.INFERRED})
        assertEquals(15L,total(next,UsageMetric.VISIBLE))
    }

    @Test(timeout=15000) fun largeCompleteHistoryPreservesEveryMillisecond(){
        val events=start+buildList{
            repeat(10000){i->val t=10L+i*4L;add(e(t,Signal.RESUME));add(e(t+2,Signal.STOP))}
        }
        val r=DualUsageEngine.parse(events,Window(0,40020))
        assertEquals(20000L,total(r,UsageMetric.PRIMARY,Evidence.INFERRED))
        assertEquals(20000L,total(r,UsageMetric.VISIBLE,Evidence.INFERRED))
        assertTrue(r.slices.none{it.app=="a"&&it.provisional})
    }

    @Test fun fastPathChecksPrimaryOverlapButAllowsAdjacentAndVisibleWindows(){
        val a=UsageSlice(UsageMetric.PRIMARY,"a",0,10,Evidence.INFERRED,"test",emptySet(),emptySet())
        assertFalse(DualIntervals.hasPrimaryOverlap(listOf(a,a.copy(start=10,end=20),a.copy(metric=UsageMetric.VISIBLE,app="b"))))
        assertTrue(DualIntervals.hasPrimaryOverlap(listOf(a,a.copy(app="b",start=9,end=20))))
        assertTrue(DualIntervals.hasPrimaryOverlap(listOf(a,a)))
    }

    @Test fun agreeingOwnAndPublicPauseClosesFocusConsistentlyAcrossWindows(){
        val ownStart=start+listOf(e(0,Signal.RESUME,source=EvidenceSource.OWN_WINDOW),e(0,Signal.FOCUS_GAIN,source=EvidenceSource.OWN_WINDOW))
        val close=listOf(e(50,Signal.FOCUS_LOSS,source=EvidenceSource.OWN_WINDOW),e(50,Signal.PAUSE,source=EvidenceSource.OWN_WINDOW),e(50,Signal.PAUSE,instance=null,source=EvidenceSource.USAGE_EVENTS))
        val full=DualUsageEngine.parse(ownStart+close,Window(0,60))
        val prefix=DualUsageEngine.parse(ownStart,Window(0,40))
        val suffix=DualUsageEngine.parse(close,Window(40,60),prefix.checkpoint)
        assertEquals(50L,total(full,UsageMetric.PRIMARY,Evidence.CONFIRMED))
        assertEquals(10L,total(suffix,UsageMetric.PRIMARY,Evidence.CONFIRMED))
        val closure=suffix.closures.single{it.metric==UsageMetric.PRIMARY&&it.focus}
        assertEquals(Evidence.CONFIRMED,closure.evidence)
        val earlier=prefix.slices.filter{it.metric==UsageMetric.PRIMARY&&it.app=="a"&&closure.origin in it.references}.sumOf{it.millis}
        assertEquals(total(full,UsageMetric.PRIMARY,Evidence.CONFIRMED),earlier+total(suffix,UsageMetric.PRIMARY,Evidence.CONFIRMED))
    }
    @Test fun conflictingClosuresHaveOneOrderIndependentConservativeVerdict(){
        val c=ClosedEvidence(UsageMetric.PRIMARY,"a","proof",50,Evidence.CONFIRMED,true,"one")
        val u=c.copy(evidence=Evidence.UNKNOWN,endRef="two")
        val a=DualUsageEngine.normalizeClosures(listOf(c,u));val b=DualUsageEngine.normalizeClosures(listOf(u,c))
        assertEquals(a,b);assertEquals(1,a.size);assertEquals(Evidence.UNKNOWN,a.single().evidence);assertEquals("one,two",a.single().endRef)
    }
    @Test fun laterPublicPauseDoesNotEraseAlreadyClosedExplicitFocus(){
        val r=parse(listOf(e(0,Signal.RESUME,source=EvidenceSource.OWN_WINDOW),e(0,Signal.FOCUS_GAIN,source=EvidenceSource.OWN_WINDOW),e(50,Signal.FOCUS_LOSS,source=EvidenceSource.OWN_WINDOW),e(50,Signal.PAUSE,source=EvidenceSource.OWN_WINDOW),e(51,Signal.PAUSE,instance=null,source=EvidenceSource.USAGE_EVENTS)),60)
        assertEquals(50L,total(r,UsageMetric.PRIMARY,Evidence.CONFIRMED))
    }


    @Test fun earlyStopFollowedByOrphanPauseCreatesOnlyALaterVisibleCandidate(){
        val r=parse(listOf(e(10,Signal.RESUME),e(20,Signal.STOP),e(40,Signal.PAUSE),e(80,Signal.STOP)))
        assertEquals(10L,total(r,UsageMetric.PRIMARY));assertEquals(50L,total(r,UsageMetric.VISIBLE))
        assertTrue(r.slices.any{it.metric==UsageMetric.VISIBLE&&it.app=="a"&&it.start==40L&&it.end==80L&&it.evidence==Evidence.INFERRED})
        assertTrue(r.diagnostics.any{it.code=="PAUSE_WITHOUT_RESUME"})
    }
}
