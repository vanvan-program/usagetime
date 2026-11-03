package dev.usagetime.app

import android.app.Application
import dev.usagetime.app.data.*

class UsageTimeApplication:Application(){
    val processSession:String=java.util.UUID.randomUUID().toString()
    val processStartedAt:Long=System.currentTimeMillis()
    val observationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val store by lazy{UsageStore(this)}
    override fun onCreate(){super.onCreate();schedule(this)}
    companion object {
        fun catchUp(context:android.content.Context){
            usageWorkScheduler(context).catchUp()
        }
        fun schedule(context:android.content.Context){
            usageWorkScheduler(context).schedule()
        }
    }
}
