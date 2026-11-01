package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test

class OwnWindowRetirementTest {
    private fun event(at:Long,signal:Signal,app:String="own",instance:String?="old",source:EvidenceSource=EvidenceSource.OWN_WINDOW,activity:String="Main")=
        ObservedEvent("$at:$signal:$app:$instance:$source",at,signal,app,activity,instance,source)
    private val global=listOf(event(0,Signal.SCREEN_ON),event(0,Signal.UNLOCK))
    private fun public(at:Long,signal:Signal,app:String="own",activity:String="Main")=
        event(at,signal,app,null,EvidenceSource.USAGE_EVENTS,activity)

    @Test fun publicStopRetiresOrphanOwnInstanceAndAllowsOtherAppPrimary(){
        val result=DualUsageEngine.parse(global+listOf(event(0,Signal.RESUME),public(10,Signal.PAUSE),
            public(11,Signal.STOP),public(12,Signal.RESUME,"other"),public(50,Signal.STOP,"other")),Window(0,60))
        assertFalse(result.checkpoint.activities.values.any{it.app=="own"&&it.source==EvidenceSource.OWN_WINDOW})
        assertEquals(38L,DualReports.summary(result.slices,UsageMetric.PRIMARY,Window(0,60),"other").inferred)
        assertFalse(result.slices.any{it.app=="own"&&it.start>=11})
        assertTrue(result.closures.any{it.app=="own"&&it.evidence==Evidence.UNKNOWN})
    }
    @Test fun publicStopDoesNotRemoveDifferentOwnActivity(){
        val result=DualUsageEngine.parse(global+listOf(event(0,Signal.RESUME,activity="Other"),public(10,Signal.STOP)),Window(0,20))
        assertEquals("Other",result.checkpoint.activities.values.single().activity)
    }
    @Test fun sameTimeOwnResumeProtectsNewInstanceFromUnresolvedPublicStop(){
        val result=DualUsageEngine.parse(global+listOf(event(0,Signal.RESUME),public(10,Signal.STOP),
            event(10,Signal.RESUME,instance="new")),Window(0,20))
        // A new own resume is authoritative for its instance even when public events are unresolved.
        assertEquals(setOf("new"),result.checkpoint.activities.values.map{it.instance}.toSet())
    }
    @Test fun splitReplayRetiresOldCheckpointWithoutExtendingVisibleTime(){
        val prefix=DualUsageEngine.parse(global+event(0,Signal.RESUME),Window(0,10))
        val suffix=DualUsageEngine.parse(listOf(public(10,Signal.STOP),public(11,Signal.RESUME,"other"),
            public(30,Signal.STOP,"other")),Window(10,40),prefix.checkpoint)
        assertFalse(suffix.slices.any{it.app=="own"})
        assertEquals(19L,DualReports.summary(suffix.slices,UsageMetric.PRIMARY,Window(10,40),"other").inferred)
    }
}
