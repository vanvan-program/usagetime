package dev.usagetime.app
import dev.usagetime.app.data.DualCodec
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class BackupProvenanceTest {
 @Test fun historicalOwnFocusWithoutMonotonicMetadataCannotBeReconfirmed(){
  val raw=dev.usagetime.app.data.StoredEvent("old",1,"FOCUS_GAIN","dev.usagetime.app","Main","old","OWN_WINDOW","UTC")
  assertEquals(dev.usagetime.core.Signal.OTHER,raw.event().signal)
  assertEquals(dev.usagetime.core.Signal.FOCUS_GAIN,raw.copy(origin=DualCodec.encodeClock(dev.usagetime.core.ClockSample(1,1,"boot"))).event().signal)
  assertEquals("FOCUS_GAIN",raw.signal)
 }
 @Test fun attributionProofsRoundTripSeparatelyFromAuditReferences(){
  val value=dev.usagetime.core.UsageSlice(dev.usagetime.core.UsageMetric.PRIMARY,"a",0,10,dev.usagetime.core.Evidence.UNKNOWN,"EXPLICIT_WINDOW_FOCUS_OPEN_TAIL",setOf(dev.usagetime.core.EvidenceSource.OWN_WINDOW),setOf("unrelated","focus"),true,setOf("focus"))
  val stored=DualCodec.stored(value)
  assertTrue(stored.refs.split(',').contains("proof:focus"))
  assertEquals(value,stored.slice())
 }
 @Test fun checkpointRetainsFocusDenialAndClockQuarantine(){
  val state=dev.usagetime.core.EngineState(deniedFocus=setOf("old"),clockTrusted=false)
  assertEquals(state,DualCodec.decode(DualCodec.encode(state)))
 }
 @Test fun ownMonotonicSamplesRoundTripAndMalformedSamplesAreRejected(){
  val sample=dev.usagetime.core.ClockSample(1000,200,"boot")
  assertEquals(sample,DualCodec.decodeClock(DualCodec.encodeClock(sample)))
  assertNull(DualCodec.decodeClock("""{"wall":-1,"elapsed":200,"boot":"boot"}"""))
  assertNull(DualCodec.decodeClock("broken"))
 }
 @Test fun legacyCoroutineActivityIsNormalizedWithoutChangingRawEvent(){
  val raw=dev.usagetime.app.data.StoredEvent("old",1,"RESUME","dev.usagetime.app","kotlinx.coroutines.StandaloneCoroutine","instance","OWN_WINDOW","Asia/Shanghai")
  assertEquals("dev.usagetime.app.MainActivity",raw.event().activity)
  assertEquals("kotlinx.coroutines.StandaloneCoroutine",raw.activity)
  assertEquals("old",raw.event().id)
  assertEquals(raw.activity,raw.copy(source="USAGE_EVENTS").event().activity)
  assertEquals(raw.activity,raw.copy(app="other").event().activity)
 }
 @Test fun repeatedRestoreExportKeepsOriginalReasonAndStableSize(){
  val original="EXPLICIT \"quote\" \\ path 中文";var reason=original
  val once=DualCodec.importReason(original,"CONFIRMED","OWN_WINDOW")
  repeat(100){reason=DualCodec.importReason(reason,if(it==0)"CONFIRMED"else"INFERRED",if(it==0)"OWN_WINDOW"else"LEGACY_IMPORT")}
  assertEquals(once,reason)
  val j=JSONObject(reason.removePrefix("IMPORTED_UNVERIFIED:"));assertEquals(original,j.getString("originalReason"));assertEquals("CONFIRMED",j.getString("originalEvidence"));assertEquals("OWN_WINDOW",j.getString("originalSources"))
 }
 @Test fun historicalNestedEnvelopeIsFlattenedWithoutPromotingStoredEvidence(){
  val oldest=DualCodec.importReason("original","CONFIRMED","OWN_WINDOW")
  val nested="IMPORTED_UNVERIFIED:"+JSONObject().put("originalEvidence","INFERRED").put("originalSources","LEGACY_IMPORT").put("originalReason",oldest)
  assertEquals(oldest,DualCodec.importReason(nested,"INFERRED","LEGACY_IMPORT"))
 }
 @Test fun unrecognizedReasonPrefixIsPreserved(){
  val raw="IMPORTED_UNVERIFIED:plain text"
  val j=JSONObject(DualCodec.importReason(raw,"UNKNOWN","LEGACY_IMPORT").removePrefix("IMPORTED_UNVERIFIED:"))
  assertEquals(raw,j.getString("originalReason"))
 }
 @Test fun rawEventOriginAlsoStaysStableAcrossRepeatedRestore(){
  val first=DualCodec.importOrigin("USAGE_EVENTS","LOCAL");var raw=first
  repeat(100){raw=DualCodec.importOrigin("LEGACY_IMPORT",raw)}
  assertEquals(first,raw)
 }
 @Test fun malformedEnvelopeFieldsRemainLiteral(){
  val raw="IMPORTED_UNVERIFIED:"+JSONObject().put("originalReason",JSONObject.NULL).put("originalSources","TEST").put("originalEvidence","UNKNOWN")
  val j=JSONObject(DualCodec.importReason(raw,"UNKNOWN","LEGACY_IMPORT").removePrefix("IMPORTED_UNVERIFIED:"))
  assertEquals(raw,j.getString("originalReason"))
 }

 @Test fun alreadyExpandedLegacyBackupCanBeRecovered(){
  var raw="original"
  repeat(14){raw="IMPORTED_UNVERIFIED:"+JSONObject().put("originalEvidence","CONFIRMED").put("originalSources","OWN_WINDOW").put("originalReason",raw)}
  assertTrue(raw.length>100000)
  val normalized=DualCodec.backupString("usage_intervals","reason",raw)
  assertEquals(DualCodec.importReason("original","CONFIRMED","OWN_WINDOW"),normalized)
 }
 @Test(expected=IllegalStateException::class) fun oversizedOrdinaryFieldsRemainRejected(){
  DualCodec.backupString("apps","label","x".repeat(100001))
 }
 @Test(expected=IllegalArgumentException::class) fun malformedOversizedProvenanceRemainsRejected(){
  DualCodec.backupString("usage_intervals","reason","IMPORTED_UNVERIFIED:"+"x".repeat(100001))
 }


 @Test fun localPrimaryCannotEraseImportedVisibleTime(){
  val local=dev.usagetime.app.data.StoredSlice("local","PRIMARY","a",0,1000,"INFERRED","local","TEST","",false,2)
  val archived=dev.usagetime.app.data.StoredSlice("archive","VISIBLE","b",0,1000,"INFERRED","archive","LEGACY_IMPORT","",false,0)
  val result=DualCodec.effective(listOf(local,archived))
  assertEquals(1000L,result.filter{it.metric==dev.usagetime.core.UsageMetric.VISIBLE}.sumOf{it.end-it.start})
 }
 @Test fun onlySameMetricLocalDomainMasksImportedIntervals(){
  val local=dev.usagetime.app.data.StoredSlice("local","VISIBLE","a",200,500,"INFERRED","local","TEST","",false,2)
  val archived=dev.usagetime.app.data.StoredSlice("archive","VISIBLE","b",0,1000,"INFERRED","archive","LEGACY_IMPORT","",false,0)
  val other=local.copy(id="other",metric="PRIMARY",start=0,end=1000)
  val result=DualCodec.effective(listOf(local,archived,other)).filter{it.metric==dev.usagetime.core.UsageMetric.VISIBLE}
  assertEquals(listOf(0L to 200L,500L to 1000L),result.filter{it.app=="b"}.map{it.start to it.end}.sortedBy{it.first})
 }

 @Test fun mergedLocalMaskMatchesMillisecondOracleAcrossGapsAndOverlaps(){
  val random=java.util.Random(123)
  repeat(100){
   val local=(0 until 20).map{i->val start=random.nextInt(100).toLong();dev.usagetime.app.data.StoredSlice("$i","VISIBLE","local",start,minOf(100,start+1+random.nextInt(20)),"INFERRED","local","TEST","",false,2)}
   val archive=dev.usagetime.app.data.StoredSlice("archive","VISIBLE","old",0,100,"INFERRED","archive","LEGACY_IMPORT","",false,0)
   val expected=(0 until 100).count{t->local.none{t.toLong()>=it.start&&t.toLong()<it.end}}.toLong()
   val result=DualCodec.effective(local+archive).filter{it.app=="old"}
   assertEquals(expected,result.sumOf{it.end-it.start})
  }
 }

 @Test fun replayMetadataRoundTripKeepsDirtyBoundary(){
  val p=dev.usagetime.core.ReplayProgress(true,123)
  assertEquals(p,DualCodec.decodeProgress(DualCodec.encodeProgress(p)))
 }
 @Test fun missingOrUnsupportedReplayMetadataFallsBackSafely(){
  assertFalse(DualCodec.decodeProgress(null).initialized)
  assertFalse(DualCodec.decodeProgress("broken").initialized)
  assertFalse(DualCodec.decodeProgress("{\"format\":\"usage-replay-progress\",\"version\":99,\"ready\":true}").initialized)
 }
 @Test fun invalidDirtyMetadataCannotActivateIncrementalReplay(){
  assertFalse(DualCodec.decodeProgress("{\"format\":\"usage-replay-progress\",\"version\":1,\"ready\":true,\"dirtyFrom\":-1}").initialized)
 }
 @Test fun committedProgressExplicitlyClearsOldDirtyBoundary(){
  val clean=dev.usagetime.core.ReplayProgress(true)
  assertEquals(clean,DualCodec.decodeProgress(DualCodec.encodeProgress(clean)))
 }
 @Test fun acquisitionClockAndZoneRoundTripWithDirtyBoundary(){
  val p=dev.usagetime.core.ReplayProgress(false,123,dev.usagetime.core.QueryBoundary(100,200,900,"old-boot","Asia/Shanghai"))
  assertEquals(p,DualCodec.decodeProgress(DualCodec.encodeProgress(p)))
 }
 @Test fun oldProgressMetadataStillLoadsWithoutFabricatingAcquisition(){
  val p=DualCodec.decodeProgress("{\"format\":\"usage-replay-progress\",\"version\":1,\"ready\":true,\"dirtyFrom\":123}")
  assertTrue(p.initialized);assertEquals(123L,p.dirtyFrom);assertNull(p.queried)
 }
 @Test fun invalidAcquisitionCannotActivateLocalReplay(){
  val j=org.json.JSONObject(DualCodec.encodeProgress(dev.usagetime.core.ReplayProgress(true,123,dev.usagetime.core.QueryBoundary(100,200,900,"boot","UTC"))))
  j.getJSONObject("queried").put("end",50)
  assertFalse(DualCodec.decodeProgress(j.toString()).initialized)
  j.getJSONObject("queried").put("end",200).put("zone","invalid-zone")
  assertFalse(DualCodec.decodeProgress(j.toString()).initialized)
 }
}
