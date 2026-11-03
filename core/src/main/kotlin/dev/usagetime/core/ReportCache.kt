package dev.usagetime.core

/** Access-ordered cache. Caller serializes access with the same lock used by data writers. */
class ReportCache<K>(private val maxEntries: Int = 12, private val maxWeight: Long = 4L * 1024 * 1024) {
    init { require(maxEntries > 0 && maxWeight > 0) }
    private val entries = LinkedHashMap<K, Pair<UsageReport, Long>>(16, 0.75f, true)
    var weight: Long = 0; private set
    var hits: Long = 0; private set
    var misses: Long = 0; private set
    var evictions: Long = 0; private set
    var invalidations: Long = 0; private set
    val size: Int get() = entries.size
    operator fun get(key: K): UsageReport? {
        val value = entries[key]?.first
        if (value == null) misses++ else hits++
        return value
    }
    fun put(key: K, report: UsageReport) {
        entries.remove(key)?.let { weight -= it.second }
        // Conservative accounting for list/map entries, objects, labels and package strings.
        val cost = 512L + report.buckets.sumOf { 160L + it.label.length * 2L } +
            report.apps.sumOf { 256L + it.app.length * 2L } +
            report.appQuality.keys.sumOf { 256L + it.length * 2L }
        if (cost > maxWeight) return
        entries[key] = report to cost; weight += cost
        val oldest = entries.iterator()
        while (entries.size > maxEntries || weight > maxWeight) {
            val entry = oldest.next(); weight -= entry.value.second; oldest.remove(); evictions++
        }
    }
    fun clear() { entries.clear(); weight = 0; invalidations++ }
}
