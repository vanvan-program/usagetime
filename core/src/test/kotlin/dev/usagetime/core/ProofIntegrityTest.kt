package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ProofIntegrityTest {
    @Test fun subSecondClockChangesAreNotHiddenByASecondsWideTolerance(){
        assertEquals("WALL_CLOCK_DISCONTINUITY",ClockDiagnosis.inspect(ClockSample(1000,100,"b"),ClockSample(1550,150,"b")))
        assertNull(ClockDiagnosis.inspect(ClockSample(1000,100,"b",10),ClockSample(1060,150,"b",10)))
    }
    @Test fun pairedSamplingRecordsSchedulingUncertainty(){
        var calls=0
        val sample=ClockSampling.capture({1000},{if(calls++==0)100 else 110},"b")
        assertEquals(105L,sample.elapsed)
        assertEquals(6L,sample.uncertainty)
    }
    private fun event(t:Long,s:Signal,instance:String="one",source:EvidenceSource=EvidenceSource.OWN_WINDOW,app:String="own")=
        ObservedEvent("$t/$s/$instance/$source/$app",t,s,app,"A",if(source==EvidenceSource.USAGE_EVENTS)null else instance,source)
    private val globals=listOf(event(0,Signal.SCREEN_ON),event(0,Signal.UNLOCK))
    private fun metric(r:EngineResult,m:UsageMetric=UsageMetric.PRIMARY,w:Window=Window(0,100),app:String?=null)=DualReports.summary(r.slices,m,w,app)
    @Test fun severalFocusedWindowsOfTheSamePackageDoNotShareOneProof(){
        fun other(t:Long,s:Signal)=event(t,s,"two").copy(activity="B")
        val r=DualUsageEngine.parse(globals+listOf(event(0,Signal.RESUME),event(0,Signal.FOCUS_GAIN),
            other(10,Signal.RESUME),other(10,Signal.FOCUS_GAIN),other(20,Signal.FOCUS_LOSS),other(20,Signal.STOP)),Window(0,30))
        assertEquals(0L,metric(r).confirmed)
        assertEquals(30L,metric(r,w=Window(0,30)).unknown)
    }

    @Test fun completedOwnHandoverDoesNotInvalidateThePreviousProof(){
        val r=DualUsageEngine.parse(globals+listOf(event(0,Signal.RESUME),event(0,Signal.FOCUS_GAIN),
            event(10,Signal.FOCUS_LOSS),event(10,Signal.STOP),event(10,Signal.RESUME,"two"),
            event(10,Signal.FOCUS_GAIN,"two"),event(20,Signal.FOCUS_LOSS,"two"),event(20,Signal.STOP,"two")),Window(0,30))
        assertEquals(20L,metric(r).confirmed)
        assertTrue(r.slices.filter{it.app=="own"&&it.evidence==Evidence.CONFIRMED}.all{it.proofRefs.size==1})
    }
    @Test fun explicitFocusDenialSurvivesACheckpointUntilNewGain(){
        val first=DualUsageEngine.parse(globals+listOf(event(0,Signal.RESUME),event(0,Signal.FOCUS_GAIN),event(10,Signal.FOCUS_LOSS)),Window(0,20))
        val next=DualUsageEngine.parse(listOf(event(40,Signal.STOP)),Window(20,50),first.checkpoint)
        assertTrue(first.checkpoint.deniedFocus.isNotEmpty())
        assertFalse(next.slices.any{it.metric==UsageMetric.PRIMARY&&it.app!=null})
        assertEquals(30L,metric(next,w=Window(20,50)).unknown)
    }
    @Test fun clockQuarantineSurvivesSeveralReplayChunks(){
        val a=DualUsageEngine.parse(listOf(event(0,Signal.CLOCK_UNCERTAIN))+globals+
            listOf(event(1,Signal.RESUME),event(1,Signal.FOCUS_GAIN)),Window(0,10))
        val b=DualUsageEngine.parse(listOf(event(20,Signal.FOCUS_LOSS),event(20,Signal.STOP)),Window(10,30),a.checkpoint)
        assertFalse(b.checkpoint.clockTrusted)
        assertEquals(20L,metric(b,w=Window(10,30)).unknown)
        assertEquals(0L,metric(b,w=Window(10,30)).confirmed)
        val c=DualUsageEngine.parse(listOf(event(30,Signal.CLOCK_TRUSTED)),Window(30,40),b.checkpoint)
        assertTrue(c.checkpoint.clockTrusted)
        assertTrue(c.checkpoint.activities.isEmpty())
        assertEquals(10L,metric(c,w=Window(30,40)).unknown)
    }
    @Test fun newOwnProcessRetiresOnlyItsOwnEvidence(){
        val first=DualUsageEngine.parse(globals+listOf(event(0,Signal.RESUME),event(0,Signal.FOCUS_GAIN),
            event(0,Signal.RESUME,source=EvidenceSource.USAGE_EVENTS,app="other")),Window(0,10))
        val next=DualUsageEngine.parse(listOf(event(10,Signal.OWN_SESSION_START,"new-process")),Window(10,20),first.checkpoint)
        assertTrue(next.checkpoint.activities.values.none{it.source==EvidenceSource.OWN_WINDOW})
        assertEquals("other",next.checkpoint.activities.values.single().app)
        assertTrue(next.closures.any{it.focus&&it.evidence==Evidence.UNKNOWN})
    }
    @Test fun publicSourceCannotImpersonateAnOwnProcessBoundary(){
        val r=DualUsageEngine.parse(globals+listOf(event(0,Signal.RESUME),
            event(10,Signal.OWN_SESSION_START,source=EvidenceSource.USAGE_EVENTS)),Window(0,20))
        assertTrue(r.checkpoint.activities.values.any{it.source==EvidenceSource.OWN_WINDOW})
    }
    @Test fun openTailsAreUnknownAndNeverIncludedInCandidateTotals(){
        val r=DualUsageEngine.parse(globals+listOf(event(10,Signal.RESUME),event(10,Signal.FOCUS_GAIN)),Window(0,100))
        val q=metric(r)
        assertEquals(0L,q.confirmed);assertEquals(0L,q.inferred)
        assertEquals(100L,q.unknown);assertTrue(q.provisional>=90L)
    }
    @Test fun closedLifecycleCandidatesDoNotEraseUnknownActualFocus(){
        val r=DualUsageEngine.parse(globals+listOf(event(10,Signal.RESUME,source=EvidenceSource.USAGE_EVENTS),
            event(40,Signal.STOP,source=EvidenceSource.USAGE_EVENTS)),Window(0,50))
        val q=metric(r,w=Window(10,40))
        assertEquals(30L,q.inferred);assertEquals(30L,q.unknown)
        assertEquals(0L,q.confirmed)
    }
    @Test fun displayProjectionPreservesUnfilteredQualityAcrossAllGrains(){
        val w=Window(0,86400000);val z=ZoneId.of("UTC")
        val data=listOf(UsageSlice(UsageMetric.PRIMARY,"a",0,10,Evidence.CONFIRMED,"closed",emptySet(),emptySet()),
            UsageSlice(UsageMetric.PRIMARY,"a",10,20,Evidence.INFERRED,"candidate",emptySet(),emptySet()),
            UsageSlice(UsageMetric.PRIMARY,"a",20,30,Evidence.UNKNOWN,"open",emptySet(),emptySet(),true))
        val cells=DailyMetrics.cells(data,UsageMetric.PRIMARY,w)
        val expected=DualReports.summary(data,UsageMetric.PRIMARY,w)
        val hour=DailyMetrics.hourReport(listOf(w to cells),w,z,null,false)
        val day=DailyMetrics.Accumulator(w,z,Grain.DAY,includeConfirmed=false).apply{addDay(cells)}.finish()
        val month=DailyMetrics.monthReport(listOf(w to cells),w,z,null,1,false)
        for(r in listOf(hour,day,month)){
            assertEquals(10L,r.total);assertEquals(expected,r.quality)
            assertEquals(expected,r.appQuality["a"])
        }
    }
    @Test fun unknownVisibleSetPreventsFalseCompleteHourlyCoverage(){
        val w=Window(0,100)
        val data=listOf(UsageSlice(UsageMetric.VISIBLE,"own",0,100,Evidence.CONFIRMED,"focus",emptySet(),emptySet()),
            UsageSlice(UsageMetric.VISIBLE,null,0,100,Evidence.UNKNOWN,"other-windows",emptySet(),emptySet()))
        val r=DailyMetrics.hourReport(listOf(w to DailyMetrics.cells(data,UsageMetric.VISIBLE,w)),w,ZoneId.of("UTC"),null)
        assertFalse(r.fullyCovered);assertFalse(r.buckets.single().complete)
    }
}
