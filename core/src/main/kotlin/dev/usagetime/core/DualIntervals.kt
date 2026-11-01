package dev.usagetime.core

/** Applies the primary exclusivity invariant even to contradictory imported history. */
object DualIntervals {
    fun hasPrimaryOverlap(input:List<UsageSlice>):Boolean {
        var end=Long.MIN_VALUE
        for(slice in input.filter{it.metric==UsageMetric.PRIMARY}.sortedBy{it.start}){
            if(slice.start<end)return true
            end=maxOf(end,slice.end)
        }
        return false
    }
    fun resolvePrimary(input:List<UsageSlice>):List<UsageSlice>{
        val primary=input.filter{it.metric==UsageMetric.PRIMARY}
        val changes=java.util.TreeMap<Long,MutableList<Pair<Int,Boolean>>>()
        primary.forEachIndexed{i,s->changes.getOrPut(s.start){mutableListOf()}.add(i to true);changes.getOrPut(s.end){mutableListOf()}.add(i to false)}
        val active=mutableMapOf<Int,UsageSlice>();val result=mutableListOf<UsageSlice>();var previous:Long?=null
        changes.forEach{(time,batch)->
            val start=previous
            if(start!=null&&time>start&&active.isNotEmpty()){
                val values=active.values.toList();val apps=values.mapNotNull{it.app}.toSet()
                val ambiguous=apps.size>1||apps.isNotEmpty()&&values.any{it.app==null}
                val best=values.sortedWith(compareBy<UsageSlice>{when(it.evidence){Evidence.CONFIRMED->0;Evidence.INFERRED->1;Evidence.UNKNOWN->2;Evidence.LEGACY->3}}.thenBy{it.reason}.thenBy{it.references.sorted().joinToString()}).first()
                result+=if(ambiguous)UsageSlice(UsageMetric.PRIMARY,null,start,time,Evidence.UNKNOWN,"PRIMARY_OVERLAP_CONFLICT",values.flatMap{it.sources}.toSet(),values.flatMap{it.references}.toSet(),values.any{it.provisional})
                else best.copy(start=start,end=time,references=values.flatMap{it.references}.toSet(),sources=values.flatMap{it.sources}.toSet(),provisional=values.any{it.provisional})
            }
            batch.filter{!it.second}.forEach{active.remove(it.first)};batch.filter{it.second}.forEach{active[it.first]=primary[it.first]};previous=time
        }
        return DualUsageEngine.merge(result+input.filter{it.metric==UsageMetric.VISIBLE})
    }
}
