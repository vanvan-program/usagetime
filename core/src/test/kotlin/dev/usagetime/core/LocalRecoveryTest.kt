package dev.usagetime.core
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*

class LocalRecoveryTest{
 @get:Rule val temp=TemporaryFolder()
 private fun bytes(text:String)="SQLite format 3\u0000$text".toByteArray()
 private fun db()=File(temp.root,"history.db").apply{writeBytes(bytes("original"))}
 private fun root()=File(temp.root,"snapshots").apply{mkdirs()}
 private fun helper(d:File,r:File,space:()->Long={Long.MAX_VALUE},step:(String)->Unit={})=LocalRecovery(r,d,space,{1000},{},step)
 @Test fun streamingCopyIsVerifiedAndRotationKeepsTwoDistinctCopies(){
  val d=db();val r=root();val h=helper(d,r)
  repeat(3){d.writeBytes(bytes("version$it"));h.create()}
  assertEquals(2,h.list().size);h.list().forEach{h.verify(it)};assertTrue(d.readBytes().contentEquals(bytes("version2")))
 }
 @Test fun failedSnapshotNeverRetiresPriorCopy(){
  val d=db();val r=root();val h=helper(d,r);val old=h.create()
  val failed=helper(d,r,step={if(it=="snapshot_verified")throw IOException("power loss")})
  assertTrue(runCatching{failed.create()}.isFailure);assertTrue(old.file.exists());h.verify(old)
 }
 @Test fun lowSpaceRefusesWritesAndKeepsDatabase(){
  val d=db();val r=root();val old=d.readBytes()
  assertTrue(runCatching{helper(d,r,space={0}).create()}.isFailure)
  assertTrue(old.contentEquals(d.readBytes()));assertTrue(r.listFiles().orEmpty().isEmpty())
 }
 @Test fun diskExhaustionDuringCopyKeepsLastGoodSnapshot(){
  val d=db();val r=root();val old=helper(d,r).create()
  val payload=ByteArray(2*1024*1024);java.util.Random(7).nextBytes(payload);d.writeBytes(bytes("large")+payload)
  var calls=0;val h=helper(d,r,space={if(++calls<=2)Long.MAX_VALUE else 0})
  assertTrue(runCatching{h.create()}.isFailure);assertTrue(old.file.exists());helper(d,r).verify(old)
 }
 @Test fun corruptGzipCannotReplaceDatabase(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"));val old=d.readBytes()
  val data=s.file.readBytes();data[data.lastIndex]=(data.last().toInt() xor 1).toByte();s.file.writeBytes(data)
  assertTrue(runCatching{h.restore(s){}}.isFailure);assertTrue(old.contentEquals(d.readBytes()));assertFalse(h.hasRollback())
 }
 @Test fun failedSqliteValidationKeepsCurrentDatabase(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"));val old=d.readBytes()
  assertTrue(runCatching{h.restore(s){throw IOException("invalid schema")}}.isFailure)
  assertTrue(old.contentEquals(d.readBytes()));assertFalse(h.hasRollback())
 }
 @Test fun failureAfterOriginalMoveRollsBackDbAndWal(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"));File(d.path+"-wal").writeText("committed WAL")
  val failed=helper(d,r,step={if(it=="restore_original_retained")throw IOException("power loss")})
  assertTrue(runCatching{failed.restore(s){}}.isFailure)
  assertTrue(d.readBytes().contentEquals(bytes("new current")));assertEquals("committed WAL",File(d.path+"-wal").readText())
 }
 @Test fun startupRecoveryRollsBackAnInterruptedPublishedRestore(){
  val d=db();val r=root();val original=d.readBytes();val rollback=File(d.path+".before-restore")
  Files.move(d.toPath(),rollback.toPath());File(rollback.path+"-wal").writeText("old WAL")
  d.writeBytes(bytes("unconfirmed replacement"));File(d.path+".restore-journal").writeText("restore")
  helper(d,r).recoverInterrupted()
  assertTrue(original.contentEquals(d.readBytes()));assertEquals("old WAL",File(d.path+"-wal").readText());assertFalse(File(d.path+".restore-journal").exists())
 }
 @Test fun invalidRecoveryJournalNeverCreatesEmptyDatabase(){
  val d=File(temp.root,"missing.db");val r=root();File(d.path+".restore-journal").writeText("bad")
  assertTrue(runCatching{helper(d,r).recoverInterrupted()}.isFailure);assertFalse(d.exists())
 }
 @Test fun successfulRestoreCanBeUndoneWithoutDiscardingEitherDatabase(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"))
  h.restore(s){};assertTrue(d.readBytes().contentEquals(bytes("original")));assertTrue(h.hasRollback())
  h.undo{};assertTrue(d.readBytes().contentEquals(bytes("new current")));assertTrue(File(d.path+".before-restore").readBytes().contentEquals(bytes("original")))
 }
 @Test fun interruptedUndoRestoresBothPriorStates(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"));h.restore(s){}
  val failed=helper(d,r,step={if(it=="undo_current_retained")throw IOException("power loss")})
  assertTrue(runCatching{failed.undo{}}.isFailure)
  assertTrue(d.readBytes().contentEquals(bytes("original")));assertTrue(File(d.path+".before-restore").readBytes().contentEquals(bytes("new current")))
 }
 @Test fun rollbackDiscardOnlyTouchesTheExplicitRollbackCopy(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("new current"));h.restore(s){}
  val current=d.readBytes();h.discardRollback();assertFalse(h.hasRollback());assertTrue(current.contentEquals(d.readBytes()));assertTrue(s.file.exists())
 }
 @Test fun rotationKeepsThePreviousVerifiedCopyWhenANewerOneIsCorrupt(){
  val d=db();val r=root();val h=helper(d,r);val good=h.create();d.writeBytes(bytes("second"));val bad=h.create()
  bad.file.writeBytes(byteArrayOf(1,2,3));d.writeBytes(bytes("third"));val newest=h.create()
  assertEquals(2,h.list().size);assertTrue(good.file.exists());assertTrue(newest.file.exists());h.list().forEach{h.verify(it)}
 }

 @Test fun staleCandidateWalCannotContaminateTheNextRestore(){
  val d=db();val r=root();val h=helper(d,r);val snapshot=h.create();d.writeBytes(bytes("current"))
  File(d.path+".restore-candidate-wal").writeText("stale WAL")
  h.restore(snapshot){}
  assertTrue(d.readBytes().contentEquals(bytes("original")));assertFalse(File(d.path+".restore-candidate-wal").exists())
 }

 @Test fun compressionDoesNotRequireAnotherUncompressedDatabaseWorthOfSpace(){
  val d=db();val r=root();d.writeBytes(bytes("compressed")+ByteArray(2*1024*1024))
  val h=helper(d,r,space={LocalRecovery.RESERVE+2*1024*1024});val s=h.create();h.verify(s)
  assertTrue(s.file.length()<1048576)
 }
 @Test fun cancelledSnapshotDoesNotPublishOrPruneOldCopies(){
  val d=db();val r=root();val h=helper(d,r);val old=h.create();d.writeBytes(bytes("changed"))
  assertTrue(runCatching{h.create{throw java.util.concurrent.CancellationException("cancel")}}.isFailure)
  assertEquals(1,h.list().size);assertTrue(old.file.exists());h.verify(old)
 }
 @Test fun cancellationBeforeRestoreMovesKeepsCurrentData(){
  val d=db();val r=root();val h=helper(d,r);val s=h.create();d.writeBytes(bytes("current"))
  assertTrue(runCatching{h.restore(s,check={throw java.util.concurrent.CancellationException("cancel")}){}}.isFailure)
  assertTrue(d.readBytes().contentEquals(bytes("current")));assertFalse(h.hasRollback())
 }


 @Test fun repeatedInterruptionKeepsQuarantinedDatabaseAndWalTogether(){
  for(mode in listOf("restore","undo")){
   val folder=File(temp.root,mode).apply{mkdirs()};val d=File(folder,"history.db");val r=File(folder,"snapshots").apply{mkdirs()}
   d.writeBytes(bytes("unconfirmed"));File(d.path+"-wal").writeText("unconfirmed WAL")
   File(d.path+".before-restore").writeBytes(bytes("original"))
   if(mode=="undo")File(d.path+".prior-rollback").writeBytes(bytes("prior rollback"))
   File(d.path+".restore-journal").writeText(mode)
   val interrupted=LocalRecovery(r,d,{Long.MAX_VALUE},{1000},syncDirectory={
    if(folder.listFiles().orEmpty().any{it.name.contains(".aborted-")&&it.name.endsWith("-wal")})throw IOException("another power interruption")
   })
   assertTrue(runCatching{interrupted.recoverInterrupted()}.isFailure)
   LocalRecovery(r,d,{Long.MAX_VALUE},{2000}).recoverInterrupted()
   val retained=folder.listFiles()!!.single{it.name.contains(".aborted-")&&!it.name.endsWith("-wal")}
   assertEquals("unconfirmed WAL",File(retained.path+"-wal").readText())
   assertArrayEquals(bytes("unconfirmed"),retained.readBytes())
   assertArrayEquals(bytes("original"),d.readBytes())
  }
 }

 @Test fun repeatedIdenticalBackupKeepsTheOlderDistinctVersion(){
  val d=db();val r=root();val h=helper(d,r);val first=h.create()
  d.writeBytes(bytes("new version"));h.create();val repeated=h.create()
  assertEquals(2,h.list().size);assertTrue(first.file.exists());assertTrue(repeated.file.exists());h.list().forEach{h.verify(it)}
 }
 @Test fun identicalBackupsStillKeepTwoVerifiedCopiesWhenNoOlderVersionExists(){
  val d=db();val r=root();val h=helper(d,r);repeat(4){h.create()}
  assertEquals(2,h.list().size);h.list().forEach{h.verify(it)}
 }
 @Test fun corruptDistinctVersionIsNotRetainedInsteadOfAUsableCopy(){
  val d=db();val r=root();val h=helper(d,r);val bad=h.create();d.writeBytes(bytes("new version"));val good=h.create()
  bad.file.writeBytes(byteArrayOf(1,2,3));val newest=h.create()
  assertEquals(2,h.list().size);assertTrue(good.file.exists());assertTrue(newest.file.exists());assertFalse(bad.file.exists());h.list().forEach{h.verify(it)}
 }
}
