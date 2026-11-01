package dev.usagetime.core

/** Durable dirty boundary; no persistent dependency on SQLite rowids or wall-clock throttling. */
data class ReplayProgress(val initialized:Boolean=false,val dirtyFrom:Long?=null,val queried:QueryBoundary?=null){
    init{require(dirtyFrom==null||dirtyFrom>=0)}
    fun markDirty(at:Long):ReplayProgress {require(at>=0);return copy(dirtyFrom=minOf(dirtyFrom?:at,at))}
    fun start(latest:Long?,overlapStart:Long,earliestNew:Long?):Long {
        require(overlapStart>=0&&(latest==null||latest>=0)&&(earliestNew==null||earliestNew>=0))
        val base=if(initialized&&latest!=null)latest else overlapStart
        return minOf(base,dirtyFrom?:base,earliestNew?:base)
    }
    /** Local replay is bounded by committed acquisition, never the retry's wall clock. */
    fun localReplay(latest:QueryBoundary?,wall:Long):LocalReplayPlan?{
        val dirty=dirtyFrom?:return null
        val target=queried?:latest?:return null
        if(wall<target.end||latest!=null&&wall<latest.end||latest!=null&&target.end<latest.end||dirty>=target.end)return null
        val baseline=if(initialized&&latest!=null)latest.end else target.start
        return LocalReplayPlan(minOf(baseline,dirty),target)
    }
}
data class QueryBoundary(val start:Long,val end:Long,val elapsed:Long,val boot:String,val zone:String){
    init{require(start>=0&&end>=start&&elapsed>=-1&&boot.isNotBlank()&&zone.isNotBlank())}
}
data class LocalReplayPlan(val start:Long,val boundary:QueryBoundary)
