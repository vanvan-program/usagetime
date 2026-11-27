package dev.usagetime.app

import android.app.Application
import android.os.SystemClock
import androidx.work.Configuration
import dev.usagetime.app.data.*
import kotlinx.coroutines.launch

class UsageTimeApplication:Application(), Configuration.Provider {
    // The scheduler initializes WorkManager from the IO scope below.
    override val workManagerConfiguration:Configuration get()=Configuration.Builder().build()
    val processSession:String=java.util.UUID.randomUUID().toString()
    val processStartedAt:Long=System.currentTimeMillis()
    internal val startupWorkGate=StartupWorkGate(SystemClock::elapsedRealtime)
    val observationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val store by lazy{UsageStore(this)}
    override fun onCreate(){
        super.onCreate()
        observationScope.launch{
            schedule(this@UsageTimeApplication)
            runCatching {
                dev.usagetime.app.sync.PhoneSyncSettings.schedule(
                    this@UsageTimeApplication,
                    dev.usagetime.app.sync.PhoneSyncSettings(this@UsageTimeApplication).load().enabled
                )
            }
        }
    }
    companion object {
        fun catchUp(context:android.content.Context){
            usageWorkScheduler(context).catchUp()
        }
        fun schedule(context:android.content.Context){
            usageWorkScheduler(context).schedule()
        }
    }
}
