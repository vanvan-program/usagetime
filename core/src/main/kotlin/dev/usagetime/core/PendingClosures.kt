package dev.usagetime.core

/** Streaming matcher. Memory depends on this replay chunk's closures, not historical rows. */
class PendingClosures(input:List<ClosedEvidence>) {
    private val closures=DualUsageEngine.normalizeClosures(input)
    private val earliest=LongArray(closures.size){Long.MAX_VALUE}
    private val candidates=closures.withIndex().groupBy{it.value.metric.name to it.value.app}
        .mapValues{(_,items)->items.groupBy{it.value.origin}}
    fun add(metric:String,app:String,start:Long,refs:String,reason:String){
        val origins=candidates[metric to app]?:return
        refs.splitToSequence(',').filter{it.startsWith(DualUsageEngine.PROOF_PREFIX)}.map{it.removePrefix(DualUsageEngine.PROOF_PREFIX)}.forEach{origin->
            origins[origin]?.forEach{(index,c)->
                if(start<c.at&&(c.focus==reason.startsWith("EXPLICIT",ignoreCase=true)))earliest[index]=minOf(earliest[index],start)
            }
        }
    }
    fun matches():List<Pair<ClosedEvidence,Long>> = closures.indices.filter{earliest[it]!=Long.MAX_VALUE}.map{closures[it] to earliest[it]}
}
