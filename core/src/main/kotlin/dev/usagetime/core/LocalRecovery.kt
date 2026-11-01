package dev.usagetime.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Private-file snapshots. Callers must close/checkpoint SQLite before invoking create/restore. */
class LocalRecovery(
    private val directory:File,private val database:File,
    private val available:()->Long={database.parentFile!!.usableSpace},
    private val clock:()->Long={System.currentTimeMillis()},
    private val syncDirectory:(File)->Unit={},
    private val step:(String)->Unit={}
){
    data class Snapshot(val file:File,val time:Long,val bytes:Long,val sha256:String)
    companion object{const val RESERVE=64L*1024*1024;private val HEADER="SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)}
    private val pattern=Regex("snapshot-(\\d+)-(\\d+)-([a-f0-9]{64})\\.db\\.gz")
    private val marker get()=File(database.parentFile,"${database.name}.restore-journal")
    private val rollback get()=File(database.parentFile,"${database.name}.before-restore")
    private val candidate get()=File(database.parentFile,"${database.name}.restore-candidate")
    fun list():List<Snapshot> = directory.listFiles().orEmpty().mapNotNull{f->
        val m=pattern.matchEntire(f.name)?:return@mapNotNull null
        val time=m.groupValues[1].toLongOrNull()?:return@mapNotNull null
        val bytes=m.groupValues[2].toLongOrNull()?:return@mapNotNull null
        if(!f.isFile||bytes<HEADER.size)return@mapNotNull null
        Snapshot(f,time,bytes,m.groupValues[3])
    }.sortedByDescending{it.time}
    private fun space(required:Long=0){
        if(required<0||available()<RESERVE||required>available()-RESERVE)throw IOException("可用空间不足，已保留原数据和现有安全副本")
    }
    private fun move(from:File,to:File){Files.move(from.toPath(),to.toPath(),ATOMIC_MOVE,REPLACE_EXISTING);syncDirectory(to.parentFile!!)}
    private fun discardStaging(file:File){
        for(suffix in listOf("","-wal","-shm","-journal"))File(file.path+suffix).delete()
    }
    private fun retainUnconfirmed(label:String,operation:String){
        // Stable across retries: a second interruption must not separate a DB from its WAL.
        val retained=File(database.parentFile,"${database.name}.$label-$operation")
        require(!retained.exists()){ "恢复保留文件存在冲突，已停止覆盖" }
        sidecars(database,retained);move(database,retained)
    }
    private fun sidecars(from:File,to:File){for(suffix in listOf("-wal","-shm")){val f=File(from.path+suffix);if(f.exists())move(f,File(to.path+suffix))}}
    private fun copyChecked(source:File,dest:File,check:()->Unit={}){
        FileInputStream(source).use{input->FileOutputStream(dest).use{out->
            val buffer=ByteArray(65536);var checked=0
            while(true){check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);checked+=n;if(checked>=1048576){space();checked=0}}
            out.fd.sync()
        }}
    }
    private fun journal(mode:String){
        val pending=File(marker.path+".pending")
        FileOutputStream(pending).use{it.write("$mode\n${java.util.UUID.randomUUID()}".toByteArray());it.fd.sync()}
        move(pending,marker)
    }
    private fun hash(d:MessageDigest)=d.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun header(file:File){DataInputStream(FileInputStream(file)).use{val bytes=ByteArray(HEADER.size);it.readFully(bytes);require(bytes.contentEquals(HEADER)){"不是SQLite数据库副本"}}}
    fun verify(snapshot:Snapshot,check:()->Unit={}){
        require(snapshot.file.canonicalFile.parentFile==directory.canonicalFile)
        val digest=MessageDigest.getInstance("SHA-256");var count=0L;val first=ByteArrayOutputStream(HEADER.size)
        FileInputStream(snapshot.file).use{raw->GZIPInputStream(raw,65536).use{input->
            val buffer=ByteArray(65536);while(true){check();val n=input.read(buffer);if(n<0)break
                if(first.size()<HEADER.size)first.write(buffer,0,minOf(n,HEADER.size-first.size()))
                count+=n;require(count<=snapshot.bytes){"安全副本长度不符"};digest.update(buffer,0,n)
            }
        }}
        require(count==snapshot.bytes&&hash(digest)==snapshot.sha256&&first.toByteArray().contentEquals(HEADER)){"安全副本校验失败"}
    }
    fun create(check:()->Unit={}):Snapshot{
        if(!directory.exists())require(directory.mkdirs()){ "无法创建安全副本目录" }
        require(directory.isDirectory);syncDirectory(directory.parentFile!!);header(database)
        val size=database.length();space(1048576)
        val temp=File(directory,"snapshot.pending");val digest=MessageDigest.getInstance("SHA-256")
        try{
            FileOutputStream(temp).use{raw->GZIPOutputStream(raw,65536).use{zip->
                FileInputStream(database).use{input->val buffer=ByteArray(65536);var sinceCheck=0
                    while(true){check();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);zip.write(buffer,0,n);sinceCheck+=n
                        if(sinceCheck>=1048576){space();sinceCheck=0}
                    }
                };zip.finish();zip.flush();raw.fd.sync()
            }}
            val result=Snapshot(temp,maxOf(clock(),(list().firstOrNull()?.time?:-1)+1),size,hash(digest));verify(result,check)
            var previous:Snapshot?=null;var identical:Snapshot?=null
            for(copy in list()){
                check()
                try{
                    verify(copy,check)
                    if(copy.sha256!=result.sha256){previous=copy;break}
                    if(identical==null)identical=copy
                }
                catch(_:java.util.zip.ZipException){/* corrupted file: try the older verified copy */}
                catch(_:EOFException){/* truncated compressed file */}
                catch(_:IllegalArgumentException){/* checksum/header mismatch */}
            }
            // Repeated manual copies must not evict the only older distinct version.
            if(previous==null)previous=identical
            check();step("snapshot_verified")
            val dest=File(directory,"snapshot-${result.time}-${result.bytes}-${result.sha256}.db.gz")
            move(temp,dest)
            val published=result.copy(file=dest)
            // Never retire the previous usable copy before the new file is verified and published.
            list().filter{it.file!=dest&&it.file!=previous?.file}.forEach{if(it.file.exists()&&!it.file.delete())throw IOException("新副本已校验，但旧副本暂时无法回收")};syncDirectory(directory)
            return published
        }finally{temp.delete()}
    }
    /** If interrupted between file moves, always restore the pre-operation database before Room opens. */
    fun recoverInterrupted(){
        File(directory,"snapshot.pending").delete()
        if(!marker.exists()){File(marker.path+".pending").delete();discardStaging(candidate);discardStaging(File(database.parentFile,"${database.name}.undo-copy"));return}
        val entry=marker.readText().split('\n')
        require(entry.size in 1..2&&entry[0] in setOf("restore","undo")&&(entry.size==1||entry[1].matches(Regex("[a-f0-9-]{36}")))){ "恢复日志无效，已停止创建空库" }
        val operation=entry.getOrNull(1)?:"legacy"
        when(entry[0]){
            "restore"->if(rollback.exists()){
                if(database.exists())retainUnconfirmed("aborted-restore",operation)
                sidecars(rollback,database);move(rollback,database)
            }else if(!database.exists()&&candidate.exists()){header(candidate);move(candidate,database)}
            "undo"->{
                val prior=File(database.parentFile,"${database.name}.prior-rollback")
                if(prior.exists()){
                    if(rollback.exists()){
                        if(database.exists())retainUnconfirmed("aborted-undo",operation)
                        sidecars(rollback,database);move(rollback,database)
                    }
                    sidecars(prior,rollback);move(prior,rollback)
                }
            }
            else->throw IOException("恢复日志无效，已停止创建空库")
        }
        require(database.exists()){ "恢复中断，原数据库尚未找到；已停止创建空库" }
        require(marker.delete()){ "无法完成恢复回退" };discardStaging(candidate);syncDirectory(database.parentFile!!)
    }
    fun hasRollback()=rollback.exists()
    /** Only call after explicit UI confirmation; never part of automatic maintenance. */
    fun discardRollback(){
        require(!marker.exists())
        for(suffix in listOf("","-wal","-shm")){val f=File(rollback.path+suffix);require(!f.exists()||f.delete()){ "无法删除恢复前副本" }}
        syncDirectory(database.parentFile!!)
    }
    fun restore(snapshot:Snapshot,check:()->Unit={},validate:(File)->Unit){
        require(!marker.exists()){ "请先完成上次恢复回退" }
        require(!rollback.exists()){ "仍保留上次恢复前数据库，请先撤销上次恢复" }
        verify(snapshot,check);space(snapshot.bytes);discardStaging(candidate)
        try{
            val restoredDigest=MessageDigest.getInstance("SHA-256")
            FileOutputStream(candidate).use{out->FileInputStream(snapshot.file).use{raw->GZIPInputStream(raw,65536).use{input->
                val buffer=ByteArray(65536);var count=0L;var checked=0
                while(true){check();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=snapshot.bytes);restoredDigest.update(buffer,0,n);out.write(buffer,0,n);checked+=n
                    if(checked>=1048576){space();checked=0}
                };require(count==snapshot.bytes&&hash(restoredDigest)==snapshot.sha256){"恢复内容校验失败"}
            }};out.fd.sync()}
            header(candidate);validate(candidate);step("restore_validated")
            // Public SQLite validation must leave a fully checkpointed candidate.
            require(File(candidate.path+"-wal").length()==0L){"恢复候选尚有未合并日志"}
            check();journal("restore")
            step("restore_journaled")
            if(database.exists())move(database,rollback)
            sidecars(database,rollback)
            step("restore_original_retained")
            move(candidate,database);step("restore_published")
            require(marker.delete());syncDirectory(database.parentFile!!)
        }catch(t:Throwable){
            try{recoverInterrupted()}catch(rollbackFailure:Throwable){t.addSuppressed(rollbackFailure)}
            throw t
        }finally{if(!marker.exists())discardStaging(candidate)}
    }
    /** Explicit undo retains the database being replaced as the next rollback copy. */
    fun undo(check:()->Unit={},validate:(File)->Unit){
        require(rollback.exists()&&!marker.exists());header(rollback)
        space(Math.addExact(rollback.length(),File(rollback.path+"-wal").length()))
        val temp=File(database.parentFile,"${database.name}.undo-copy");discardStaging(temp)
        try{
            copyChecked(rollback,temp,check)
            val oldWal=File(rollback.path+"-wal")
            if(oldWal.exists())copyChecked(oldWal,File(temp.path+"-wal"),check)
            validate(temp)
            require(File(temp.path+"-wal").length()==0L)
            // Preserve both databases, and use the same journaled replacement protocol.
            val retained=File(database.parentFile,"${database.name}.prior-rollback")
            check();journal("undo")
            try{
                move(rollback,retained);sidecars(rollback,retained);step("undo_prior_retained")
                move(database,rollback);sidecars(database,rollback);step("undo_current_retained")
                move(temp,database)
                require(marker.delete());syncDirectory(database.parentFile!!)
                retained.delete();File(retained.path+"-wal").delete();File(retained.path+"-shm").delete()
            }catch(t:Throwable){recoverInterrupted();throw t}
        }finally{if(!marker.exists())discardStaging(temp)}
    }
}
