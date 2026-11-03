package dev.usagetime.app

import kotlinx.coroutines.*

/** Called on the model's dispatcher; the supplied scope owns every request. */
internal class ReportRequestRunner<T>(
    private val scope:CoroutineScope,
    private val onComputing:(Boolean)->Unit,
    private val onError:(Exception)->Unit,
    private val beginTrace:(Int)->Unit,
    private val endTrace:(Int)->Unit
){
    private var job:Job?=null
    private var sequence=0

    fun cancel(){job?.cancel()}
    fun stop(){cancel();onComputing(false)}
    suspend fun join(){job?.join()}

    fun submit(load:suspend ()->T,commit:(T)->Unit){
        cancel()
        val request=++sequence
        job=scope.launch{
            beginTrace(request)
            onComputing(true)
            try{
                val result=load()
                ensureActive()
                commit(result)
            }catch(_:CancellationException){}catch(e:Exception){onError(e)}
            finally{
                endTrace(request)
                // A cancelled request must not clear a newer request's loading indicator.
                if(isActive)onComputing(false)
            }
        }
    }
}
