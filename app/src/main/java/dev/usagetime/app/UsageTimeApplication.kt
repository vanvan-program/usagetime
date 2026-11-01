package dev.usagetime.app

import android.app.Application
import androidx.work.*
import dev.usagetime.app.data.*
import java.util.concurrent.TimeUnit

class UsageTimeApplication:Application(){
    val processSession:String=java.util.UUID.randomUUID().toString()
    val processStartedAt:Long=System.currentTimeMillis()
    val observationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val store by lazy{UsageStore(this)}
    override fun onCreate(){super.onCreate();schedule(this)}
    companion object {
        fun catchUp(context:android.content.Context){
            // KEEP coalesces boot/unlock/update broadcasts; a running collection is never replaced.
            WorkManager.getInstance(context).enqueueUniqueWork("catch-up-usage",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UsageWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
        fun schedule(context:android.content.Context){
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("save-usage",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageWorker>(6,TimeUnit.HOURS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build())
        }
    }
}
