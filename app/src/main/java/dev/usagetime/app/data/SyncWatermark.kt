package dev.usagetime.app.data

import dev.usagetime.core.AppCategory
import dev.usagetime.core.Dates
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

internal data class WatermarkEntry(val seq: Long, val minMillis: Long?)

internal data class SyncWatermarkSnapshot(
    val seq: Long,
    val fullSeq: Long,
    val labelsVersion: Long
)

/** Bounded changelog of report mutations so incremental sync skips unmodified historical days. */
internal class SyncWatermarkTracker(
    initialSeq: Long = 0L,
    initialFullSeq: Long = 0L,
    initialLabelsVersion: Long = 0L,
    initialEntries: List<WatermarkEntry> = emptyList(),
    private val capacity: Int = DEFAULT_CAPACITY
) {
    var seq: Long = initialSeq
        private set
    var fullSeq: Long = initialFullSeq
        private set
    var labelsVersion: Long = initialLabelsVersion
        private set
    private val entries = ArrayDeque<WatermarkEntry>(capacity)

    init {
        require(capacity > 0)
        initialEntries.takeLast(capacity).forEach { entries.addLast(it) }
    }

    @Synchronized
    fun snapshot(): SyncWatermarkSnapshot = SyncWatermarkSnapshot(seq, fullSeq, labelsVersion)

    @Synchronized
    fun recordIncremental(minMillis: Long) {
        seq += 1L
        entries.addLast(WatermarkEntry(seq, minMillis))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun recordFull() {
        seq += 1L
        fullSeq = seq
        entries.addLast(WatermarkEntry(seq, null))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun bumpLabels() {
        labelsVersion += 1L
    }

    /**
     * Returns:
     * - `Long.MAX_VALUE` when nothing in `(sinceSeq, seq]` mutated reports
     * - earliest mutated timestamp when all changes since `sinceSeq` are incremental and retained
     * - `null` when a full history scan is required
     */
    @Synchronized
    fun dirtyFrom(sinceSeq: Long): Long? {
        if (sinceSeq < 0L || sinceSeq > seq) return null
        if (sinceSeq == seq) return Long.MAX_VALUE
        if (sinceSeq < fullSeq) return null
        val oldestKept = entries.firstOrNull()?.seq ?: return null
        if (sinceSeq + 1L < oldestKept) return null
        var earliest = Long.MAX_VALUE
        for (entry in entries) {
            if (entry.seq > sinceSeq) {
                val millis = entry.minMillis ?: return null
                if (millis < earliest) earliest = millis
            }
        }
        return earliest
    }

    @Synchronized
    fun encode(): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("s", entry.seq).put("m", entry.minMillis ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("seq", seq)
            .put("fullSeq", fullSeq)
            .put("labelsVersion", labelsVersion)
            .put("entries", array)
            .toString()
    }

    companion object {
        const val DEFAULT_CAPACITY = 64

        fun decode(raw: String?, capacity: Int = DEFAULT_CAPACITY): SyncWatermarkTracker {
            if (raw.isNullOrBlank()) return SyncWatermarkTracker(capacity = capacity)
            return runCatching {
                val obj = JSONObject(raw)
                val seq = obj.optLong("seq", 0L).coerceAtLeast(0L)
                val fullSeq = obj.optLong("fullSeq", 0L).coerceAtLeast(0L)
                val labelsVersion = obj.optLong("labelsVersion", 0L).coerceAtLeast(0L)
                val arr = obj.optJSONArray("entries")
                val parsed = if (arr == null) emptyList() else List(arr.length()) { index ->
                    val item = arr.getJSONObject(index)
                    WatermarkEntry(
                        seq = item.getLong("s"),
                        minMillis = if (item.isNull("m")) null else item.getLong("m")
                    )
                }
                SyncWatermarkTracker(seq, fullSeq, labelsVersion, parsed, capacity)
            }.getOrElse { SyncWatermarkTracker(capacity = capacity) }
        }
    }
}

internal object PhoneSyncHelpers {
    private val hexDigits = "0123456789abcdef".toCharArray()
    private val sha256 = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = sha256.get()!!.apply { reset() }.digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val n = digest[i].toInt() and 255
            out[i * 2] = hexDigits[n ushr 4]
            out[i * 2 + 1] = hexDigits[n and 15]
        }
        return String(out)
    }

    fun categoryDigest(categories: Map<String, AppCategory>): String {
        if (categories.isEmpty()) return "empty"
        val canonical = categories.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value.name}" }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    fun manifestDigest(
        deviceId: String,
        deviceName: String,
        zoneId: String,
        days: Map<String, String>
    ): String {
        val canonical = buildString {
            append(deviceId).append('\u0000')
                .append(deviceName).append('\u0000')
                .append(zoneId).append('\u0000')
                .append(days.size)
            for (key in days.keys.sorted()) {
                append('\n').append(key).append('=').append(days[key])
            }
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    fun skipBeforeDate(
        first: LocalDate,
        today: LocalDate,
        republish: Boolean,
        remoteMissing: Boolean,
        scopeMatches: Boolean,
        categoryMatches: Boolean,
        labelsMatch: Boolean,
        syncedEndDate: LocalDate?,
        dirtyMillis: Long?,
        zone: ZoneId
    ): LocalDate {
        if (republish || remoteMissing || !scopeMatches || !categoryMatches || !labelsMatch || syncedEndDate == null || dirtyMillis == null) {
            return first
        }
        val dirtyDate = if (dirtyMillis == Long.MAX_VALUE) syncedEndDate else Dates.date(dirtyMillis, zone)
        return maxOf(first, minOf(dirtyDate, syncedEndDate, today))
    }

    fun shouldUploadManifest(
        republish: Boolean,
        remoteMissing: Boolean,
        changedDays: Int,
        prevManifestMeta: String?,
        nextManifestMeta: String
    ): Boolean = republish || remoteMissing || changedDays > 0 || prevManifestMeta != nextManifestMeta
}
