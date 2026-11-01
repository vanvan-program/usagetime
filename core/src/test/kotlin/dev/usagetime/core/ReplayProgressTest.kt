package dev.usagetime.core
import org.junit.Test
import org.junit.Assert.*
class ReplayProgressTest {
 @Test fun oldVersionWithoutProgressRetainsFullOverlap(){assertEquals(100L,ReplayProgress().start(1000,100,null))}
 @Test fun unchangedQueryStartsAtLastSuccessfulBoundary(){assertEquals(1000L,ReplayProgress(true).start(1000,100,null))}
 @Test fun lateSystemEventsRewindBeforeThePreviousCheckpoint(){assertEquals(300L,ReplayProgress(true).start(1000,100,300))}
 @Test fun lateOwnEventsCanRewindBeyondTheSystemQueryOverlap(){assertEquals(50L,ReplayProgress(true,50).start(1000,100,null))}
 @Test fun dirtyAndNewBoundariesChooseEarliestEvidence(){assertEquals(200L,ReplayProgress(true,500).start(1000,100,200))}
 @Test fun newFutureEventsDoNotSkipFromLatestBoundary(){assertEquals(1000L,ReplayProgress(true).start(1000,100,1200))}
 @Test fun dirtyUpdatesNeverMoveAnOlderBoundaryForward(){assertEquals(20L,ReplayProgress(true,20).markDirty(50).markDirty(30).dirtyFrom)}
 @Test fun localReplayStopsAtAcquiredEndAfterLongDelay(){
  val acquired=QueryBoundary(100,200,1000,"boot-a","UTC")
  val plan=ReplayProgress(true,120,acquired).localReplay(QueryBoundary(100,100,900,"boot-a","UTC"),200+7*86400000L)!!
  assertEquals(100L,plan.start);assertEquals(acquired,plan.boundary)
 }
 @Test fun firstPendingReplayDoesNotStartAtUnixEpoch(){
  val acquired=QueryBoundary(1_700_000_000_000,1_700_100_000_000,1000,"boot-a","UTC")
  val plan=ReplayProgress(false,acquired.start+10,acquired).localReplay(null,acquired.end+100)!!
  assertEquals(acquired.start,plan.start)
 }
 @Test fun rollbackBeforeAcquisitionEndLeavesPendingIntact(){
  val p=ReplayProgress(true,120,QueryBoundary(100,200,1000,"boot-a","UTC"))
  assertNull(p.localReplay(QueryBoundary(100,100,900,"boot-a","UTC"),150))
  assertEquals(120L,p.dirtyFrom);assertEquals(200L,p.queried!!.end)
 }
 @Test fun retryAtSameWallTimeCanCompleteAlreadyAcquiredRange(){
  val acquired=QueryBoundary(100,200,1000,"boot-a","UTC")
  assertNotNull(ReplayProgress(true,120,acquired).localReplay(QueryBoundary(100,100,900,"boot-a","UTC"),200))
 }
 @Test fun legacyPendingOnlyReplaysToExistingCheckpoint(){
  val latest=QueryBoundary(200,200,1000,"boot-a","UTC")
  val plan=ReplayProgress(true,120).localReplay(latest,1000000)!!
  assertEquals(120L,plan.start);assertEquals(200L,plan.boundary.end)
 }
 @Test fun newOwnEventCannotExtendTheAcquiredDomain(){
  assertNull(ReplayProgress(true,201,QueryBoundary(100,200,1000,"boot-a","UTC")).localReplay(QueryBoundary(100,100,900,"boot-a","UTC"),10000))
 }
 @Test fun missingAcquisitionAndCheckpointKeepUninitializedPending(){
  assertNull(ReplayProgress(false,120).localReplay(null,10000))
 }
 @Test fun staleAcquisitionCannotRewindProcessedEnd(){
  assertNull(ReplayProgress(true,120,QueryBoundary(100,200,1000,"boot-a","UTC")).localReplay(QueryBoundary(300,300,1100,"boot-a","UTC"),10000))
 }
 @Test fun acquiredPlanLimitsActualOpenSessionDurationEvenAfterDays(){
  val pending=ReplayProgress(false,100,QueryBoundary(100,200,1000,"boot-a","UTC"))
  val plan=pending.localReplay(null,200+7*86400000L)!!
  val raw=listOf(ObservedEvent("on",100,Signal.SCREEN_ON),ObservedEvent("unlock",100,Signal.UNLOCK),ObservedEvent("resume",110,Signal.RESUME,"a","A"))
  val parsed=DualUsageEngine.parse(raw,Window(plan.start,plan.boundary.end))
  for(metric in UsageMetric.entries){
   assertEquals(90L,parsed.slices.filter{it.metric==metric&&it.app=="a"}.sumOf{it.millis})
   assertTrue(parsed.slices.all{it.end<=200})
  }
 }
}
