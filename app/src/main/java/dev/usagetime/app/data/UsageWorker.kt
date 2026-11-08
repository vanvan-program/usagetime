package dev.usagetime.app.data

import android.content.*
import androidx.work.*
import dev.usagetime.app.UsageTimeApplication

class UsageWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=try {
        if(!applicationContext.getSystemService(android.os.UserManager::class.java).isUserUnlocked)throw SyncUnavailable("用户解锁后补记")
        val store=(applicationContext as UsageTimeApplication).store
        if(store.recording)store.collect()
        dev.usagetime.app.UsageWidgetProvider.requestUpdate(applicationContext)
        Result.success()
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:SyncUnavailable){Result.retry()}catch(_:SecurityException){Result.success()}catch(_:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
}
class RescheduleReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){usageWorkScheduler(context).onBroadcast(intent.action)}
}
