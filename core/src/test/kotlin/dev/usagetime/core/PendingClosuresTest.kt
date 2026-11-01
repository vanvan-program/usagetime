package dev.usagetime.core
import org.junit.Test
import org.junit.Assert.*

class PendingClosuresTest {
 private fun close(metric:UsageMetric=UsageMetric.PRIMARY,app:String="a",origin:String="ref",focus:Boolean=true,at:Long=100)=ClosedEvidence(metric,app,origin,at,Evidence.CONFIRMED,focus,"ending")
 @Test fun exactOriginMetricAppAndStrictTimeBoundaryAreRequired(){
  val c=close();val p=PendingClosures(listOf(c))
  p.add("PRIMARY","a",0,"proof:ref-extra","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  p.add("VISIBLE","a",0,"proof:ref","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  p.add("PRIMARY","b",0,"proof:ref","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  p.add("PRIMARY","a",100,"proof:ref","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  assertTrue(p.matches().isEmpty())
  p.add("PRIMARY","a",99,"other,proof:ref,tail","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  assertEquals(listOf(c to 99L),p.matches())
 }
 @Test fun focusAndLifecycleProofsNeverFinalizeEachOthersRows(){
  val focus=close();val lifecycle=close(focus=false);val p=PendingClosures(listOf(focus,lifecycle))
  p.add("PRIMARY","a",2,"proof:ref","SINGLE_RESUMED_OPEN_TAIL")
  assertEquals(listOf(lifecycle to 2L),p.matches())
  p.add("PRIMARY","a",4,"proof:ref","explicit_window_focus_open_tail")
  assertEquals(listOf(focus to 4L,lifecycle to 2L),p.matches())
 }
 @Test fun minimumStartAndConservativeNormalizedClosureArePreserved(){
  val c=close();val unknown=c.copy(evidence=Evidence.UNKNOWN,endRef="uncertain")
  val p=PendingClosures(listOf(c,unknown))
  for(start in listOf(50L,10L,20L))p.add("PRIMARY","a",start,"proof:ref","EXPLICIT")
  val match=p.matches().single();assertEquals(10L,match.second);assertEquals(Evidence.UNKNOWN,match.first.evidence)
  assertEquals("ending,uncertain",match.first.endRef)
 }
 @Test(timeout=15000) fun largeUnrelatedHistoryDoesNotProduceExtraMatches(){
  val p=PendingClosures((0 until 10).map{close(origin="wanted$it")})
  repeat(100000){i->p.add("PRIMARY","a",0,"history$i,other$i","EXPLICIT")}
  assertTrue(p.matches().isEmpty())
  repeat(10){i->p.add("PRIMARY","a",1,"proof:wanted$i","EXPLICIT")}
  assertEquals(10,p.matches().size)
 }
 @Test fun auditReferencesCannotImpersonateAttributionProofs(){
  val p=PendingClosures(listOf(close()))
  p.add("PRIMARY","a",0,"ref,other","EXPLICIT_WINDOW_FOCUS_OPEN_TAIL")
  assertTrue(p.matches().isEmpty())
 }
}
