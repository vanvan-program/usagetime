package dev.usagetime.app.data

import dev.usagetime.core.Evidence
import dev.usagetime.core.EvidenceSource
import dev.usagetime.core.Signal
import dev.usagetime.core.UsageMetric
import org.json.JSONObject

/** Converts backup rows only; the caller owns batching, transactions and stream validation. */
internal class BackupRecordDecoder(private val latestAllowed: Long) {
    fun validateRange(start: Long, end: Long) {
        require(start >= 0 && end > start && end <= latestAllowed) { "备份区间无效" }
    }

    fun interval(row: JSONObject): SavedInterval {
        val pkg = row.getString("app")
        require(pkg.isNotBlank() && pkg.length <= 255)
        val a = row.getLong("start"); val b = row.getLong("end")
        validateRange(a, b)
        return SavedInterval(pkg, a, b)
    }

    fun coverage(row: JSONObject): SavedCoverage {
        val a = row.getLong("start"); val b = row.getLong("end")
        validateRange(a, b)
        return SavedCoverage(a, b, row.getBoolean("reliable"))
    }

    fun app(row: JSONObject): SavedApp {
        val pkg = row.getString("app")
        require(pkg.isNotBlank() && pkg.length <= 255)
        return SavedApp(pkg, row.getString("label").take(120))
    }

    fun event(row: JSONObject): StoredEvent {
        val time = row.getLong("time")
        require(time >= 0 && time <= latestAllowed)
        val signal = Signal.valueOf(row.getString("signal"))
        // Imported events remain audit evidence and never become this device's live state.
        return StoredEvent(
            "archive:" + row.getString("id").removePrefix("archive:"), time, signal.name,
            row.getString("app"), row.getString("activity"),
            if (row.isNull("instance")) null else row.getString("instance"),
            EvidenceSource.LEGACY_IMPORT.name, row.getString("zone"),
            DualCodec.importOrigin(row.getString("source"), row.optString("origin", "LOCAL")),
            if (row.isNull("rawType")) null else row.getInt("rawType"),
        )
    }

    fun slice(row: JSONObject): StoredSlice {
        val a = row.getLong("start"); val b = row.getLong("end")
        validateRange(a, b)
        val metric = UsageMetric.valueOf(row.getString("metric"))
        val original = Evidence.valueOf(row.getString("evidence"))
        val pkg = if (row.isNull("app")) null else row.getString("app")
        require(pkg == null || pkg.length <= 255)
        val grade = if (original == Evidence.CONFIRMED || original == Evidence.INFERRED) Evidence.INFERRED else original
        val id = "archive:" + row.getString("id").removePrefix("archive:")
        return StoredSlice(
            id, metric.name, pkg, a, b, grade.name,
            DualCodec.importReason(row.getString("reason"), original.name, row.getString("sources")),
            EvidenceSource.LEGACY_IMPORT.name,
            row.optString("refs", "").split(',').filter { it.isNotBlank() }
                .joinToString(",") { "archive:" + it.removePrefix("archive:") },
            row.optBoolean("provisional", false), 0,
        )
    }
}
