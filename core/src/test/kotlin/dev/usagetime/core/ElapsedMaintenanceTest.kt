package dev.usagetime.core

import org.junit.Test
import org.junit.Assert.*

class ElapsedMaintenanceTest {
 @Test fun firstRunAndChangedOrUnknownBootAlwaysRun(){
  assertTrue(ElapsedMaintenance.due(10,1,-1,-2))
  assertTrue(ElapsedMaintenance.due(10,2,5,1))
  assertTrue(ElapsedMaintenance.due(10,-1,5,-1))
 }
 @Test fun repeatedSyncsWaitForExactElapsedBoundary(){
  assertFalse(ElapsedMaintenance.due(99,1,0,1,100))
  assertTrue(ElapsedMaintenance.due(100,1,0,1,100))
  assertTrue(ElapsedMaintenance.due(101,1,0,1,100))
 }
 @Test fun resetElapsedClockCannotDelayMaintenance(){
  assertTrue(ElapsedMaintenance.due(5,1,100,1))
  assertFalse(ElapsedMaintenance.due(100,1,100,1))
 }
 @Test fun largeMonotonicValuesRemainSafe(){
  assertTrue(ElapsedMaintenance.due(Long.MAX_VALUE,1,0,1))
  assertFalse(ElapsedMaintenance.due(Long.MAX_VALUE,1,Long.MAX_VALUE-1,1))
 }
}
