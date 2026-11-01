package dev.usagetime.core

import dev.usagetime.core.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import kotlin.random.Random

class AuditReplayTest {
    private fun e(t:Long,s:Signal,app:String="",instance:String?=null,source:EvidenceSource=EvidenceSource.USAGE_EVENTS,activity:String="A",id:String="$t/$s/$app/$instance/$source") =
        ObservedEvent(id,t,s,app,activity,instance,source)
    private fun globals(at:Long=0)=listOf(e(at,Signal.SCREEN_ON),e(at,Signal.UNLOCK))
    private fun own(t:Long,s:Signal,instance:String="one")=e(t,s,"own",instance,EvidenceSource.OWN_WINDOW)
    private fun parse(events:List<ObservedEvent>,end:Long=100)=DualUsageEngine.parse(events,Window(0,end))
    private fun sum(r:EngineResult,metric:UsageMetric,app:String,grade:Evidence?=null,start:Long=0,end:Long=100)=
        Reports.unionDuration(r.slices.filter{it.metric==metric&&it.app==app&&(grade==null||it.evidence==grade)}.map{Window(it.start,it.end)},Window(start,end))
    private fun unknown(r:EngineResult,m:UsageMetric,w:Window)=DualReports.summary(r.slices,m,w).unknown

    @Test fun C01_unorderedInputAndDuplicatesAreInvariant() {
        val events=globals()+listOf(e(10,Signal.RESUME,"a"),e(30,Signal.PAUSE,"a"),e(60,Signal.STOP,"a"))
        val expected=parse(events)
        repeat(100){assertEquals(expected,parse((events+events).shuffled(Random(it))))}
    }
    @Test fun C02_simultaneousConflictingLifecycleStaysUnknown() {
        val r=parse(globals()+listOf(e(10,Signal.RESUME,"a"),e(30,Signal.PAUSE,"a"),e(30,Signal.RESUME,"a")))
        assertTrue(r.diagnostics.any{it.code=="SAME_TIME_LIFECYCLE_CONFLICT"})
        assertEquals(70L,unknown(r,UsageMetric.PRIMARY,Window(30,100)))
    }
    @Test fun C03_missingStartNeverBackfillsPrimary() {
        val r=parse(globals()+listOf(e(30,Signal.PAUSE,"a"),e(60,Signal.STOP,"a")))
        assertEquals(0L,sum(r,UsageMetric.PRIMARY,"a"))
        assertEquals(30L,sum(r,UsageMetric.VISIBLE,"a",Evidence.INFERRED))
    }
    @Test fun C04_splitScreenIsUnknownPrimaryAndOverlappingVisibleCandidates() {
        val r=parse(globals()+listOf(e(10,Signal.RESUME,"a"),e(20,Signal.RESUME,"b"),e(60,Signal.STOP,"a"),e(70,Signal.STOP,"b")))
        assertEquals(40L,unknown(r,UsageMetric.PRIMARY,Window(20,60)))
        assertEquals(50L,sum(r,UsageMetric.VISIBLE,"a"))
        assertEquals(50L,sum(r,UsageMetric.VISIBLE,"b"))
    }
    @Test fun C05_pipPauseRetainsOnlyCandidateVisibility() {
        val r=parse(globals()+listOf(e(10,Signal.RESUME,"video"),e(20,Signal.PAUSE,"video"),e(21,Signal.RESUME,"reader"),e(60,Signal.STOP,"video")))
        assertEquals(0L,sum(r,UsageMetric.PRIMARY,"video",start=20,end=60))
        assertEquals(40L,sum(r,UsageMetric.VISIBLE,"video",Evidence.INFERRED,start=20,end=60))
        assertEquals(40L,unknown(r,UsageMetric.VISIBLE,Window(20,60)))
    }
    @Test fun C06_lockAndAmbientDoNotClaimInvisible() {
        val r=parse(globals()+listOf(e(10,Signal.RESUME,"a"),e(20,Signal.LOCK),e(40,Signal.NON_INTERACTIVE)))
        assertEquals(80L,unknown(r,UsageMetric.VISIBLE,Window(20,100)))
    }
    @Test fun C07_rebootDoesNotContinueOldOwner() {
        val r=parse(globals()+listOf(e(10,Signal.RESUME,"a"),e(30,Signal.STARTUP),e(60,Signal.RESUME,"b")))
        assertEquals(0L,sum(r,UsageMetric.PRIMARY,"a"))
        assertEquals(70L,unknown(r,UsageMetric.PRIMARY,Window(30,100)))
    }
    @Test fun C08_civilMidnightAndDstPreserveDuration() {
        for(z in listOf(ZoneId.of("Asia/Shanghai"),ZoneId.of("America/New_York"))) {
            val start=LocalDate.of(2026,10,31).atTime(23,30).atZone(z).toInstant().toEpochMilli()
            val end=LocalDate.of(2026,11,2).atTime(0,30).atZone(z).toInstant().toEpochMilli()
            val s=UsageSlice(UsageMetric.VISIBLE,"a",start,end,Evidence.INFERRED,"candidate",emptySet(),emptySet())
            val w=Window(start,end)
            assertEquals(w.duration,Reports.slots(w,z,Grain.HOUR).sumOf{it.duration})
            assertEquals(w.duration,DualReports.daily(listOf(s),UsageMetric.VISIBLE,w,z).sumOf{it.second.inferred})
        }
    }
    @Test fun C09_elapsedClockDetectsJumpAndReboot() {
        val previous=ClockSample(1000,100,"boot1")
        assertEquals("WALL_CLOCK_BACKWARD",ClockDiagnosis.inspect(previous,ClockSample(900,200,"boot1")))
        assertEquals("WALL_CLOCK_DISCONTINUITY",ClockDiagnosis.inspect(previous,ClockSample(3601100,200,"boot1")))
        assertEquals("BOOT_CHANGED",ClockDiagnosis.inspect(previous,ClockSample(1200,50,"boot2")))
    }
    @Test fun C10_processRestartRetainsAcquiredReplayEnd() {
        val acquired=QueryBoundary(0,100,200,"boot","UTC")
        val plan=ReplayProgress(true,10,acquired).localReplay(QueryBoundary(0,20,120,"boot","UTC"),100000)!!
        assertEquals(100L,plan.boundary.end)
    }
    @Test fun F01_explicitFocusLossMustNotBecomePrimaryCandidate() {
        val r=parse(globals()+listOf(own(10,Signal.RESUME),own(10,Signal.FOCUS_GAIN),own(30,Signal.FOCUS_LOSS),own(70,Signal.STOP)))
        assertEquals("Own focus is explicitly absent on [30,70); primary attribution must be unknown",0L,sum(r,UsageMetric.PRIMARY,"own",start=30,end=70))
    }
    @Test fun F02_unrelatedFocusBoundaryCannotConfirmAnOpenSession() {
        val r=parse(globals()+listOf(own(0,Signal.RESUME),own(0,Signal.FOCUS_GAIN),
            own(10,Signal.RESUME,"two"),own(10,Signal.FOCUS_GAIN,"two"),
            own(20,Signal.FOCUS_LOSS,"two"),own(20,Signal.STOP,"two")),30)
        assertEquals("Instance one has no closing focus evidence; instance two must not close it via boundaryRefs",0L,
            sum(r,UsageMetric.PRIMARY,"own",Evidence.CONFIRMED,0,10))
    }
    @Test fun F03_missingOwnCloseAtProcessDeathCannotAttributeAcrossNewInstance() {
        val r=parse(globals()+listOf(own(0,Signal.RESUME),own(0,Signal.FOCUS_GAIN),
            own(40,Signal.RESUME,"new-process"),own(40,Signal.FOCUS_GAIN,"new-process"),
            own(60,Signal.FOCUS_LOSS,"new-process"),own(60,Signal.STOP,"new-process")),100)
        assertEquals("Old process has no stop/focus-loss and must not be revived as attributed use after the new process stops",0L,
            sum(r,UsageMetric.PRIMARY,"own",start=60,end=100))
    }
    @Test fun F04_clockGapAtPreviousCheckpointDoesNotQuarantineJumpRange() {
        // Collection now records a typed uncertain clock domain at latest.time.
        val now=3600200L
        val events=listOf(e(100,Signal.CLOCK_UNCERTAIN,source=EvidenceSource.OWN_WINDOW))+
            globals(110)+listOf(e(120,Signal.RESUME,"a"),e(3600180,Signal.STOP,"a"))
        val r=DualUsageEngine.parse(events,Window(100,now))
        assertEquals("The clock discontinuity has no located jump instant; wall-clock subtraction cannot establish duration",0L,
            sum(r,UsageMetric.PRIMARY,"a",start=100,end=now))
    }
    @Test fun F05_emptyQueryAndMissingStopMustNotExtendKnownUse() {
        val prefix=parse(globals()+listOf(e(10,Signal.RESUME,"a")),20)
        val suffix=DualUsageEngine.parse(emptyList(),Window(20,100),prefix.checkpoint)
        assertEquals("No system evidence distinguishes continuing use from dropped stop/process death",80L,
            unknown(suffix,UsageMetric.PRIMARY,Window(20,100)))
    }
    @Test fun F06_unresolvedRepeatedResumeRequiresAmbiguity() {
        val r=parse(globals()+listOf(e(0,Signal.RESUME,"a"),e(10,Signal.RESUME,"a"),e(20,Signal.PAUSE,"a")))
        assertTrue(r.diagnostics.any{it.code=="REPEATED_RESUME_OR_INSTANCE_COLLISION"})
        assertEquals("Repeated unresolved resumes may represent different instances; the collision interval must be unknown",10L,
            unknown(r,UsageMetric.PRIMARY,Window(10,20)))
    }
    @Test fun C11_ownMatchedFocusCanConfirmOnlyItsClosedSpan() {
        val r=parse(globals()+listOf(own(10,Signal.RESUME),own(10,Signal.FOCUS_GAIN),
            own(30,Signal.FOCUS_LOSS),own(30,Signal.STOP)))
        assertEquals(20L,sum(r,UsageMetric.PRIMARY,"own",Evidence.CONFIRMED))
        assertEquals(20L,sum(r,UsageMetric.VISIBLE,"own",Evidence.CONFIRMED))
    }
}
