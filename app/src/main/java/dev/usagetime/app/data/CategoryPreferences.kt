package dev.usagetime.app.data

import android.annotation.SuppressLint
import android.content.Context
import dev.usagetime.core.AppCategory
import org.json.JSONObject

class CategoryPreferences(context: Context, name: String = "usage-categories") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cachedMap: Map<String, AppCategory>? = null

    fun read(): Map<String, AppCategory> = synchronized(this) {
        val raw = preferences.getString("assignments", "{}") ?: "{}"
        val existing = cachedMap
        if (existing != null && cachedRaw == raw) return existing
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val parsed = json.keys().asSequence().associateWith { AppCategory.fromKey(json.optString(it)) }
        cachedRaw = raw
        cachedMap = parsed
        parsed
    }

    // Preserves synchronous commit() Boolean check; KTX edit(commit = true) discards the failure signal.
    @SuppressLint("UseKtx")
    private fun write(assignments: Map<String, AppCategory>): Map<String, AppCategory> {
        val json = JSONObject()
        assignments.forEach { (key, value) -> json.put(key, value.name) }
        val encoded = json.toString()
        check(preferences.edit().putString("assignments", encoded).commit()) { "无法保存分类设置" }
        cachedRaw = encoded
        cachedMap = assignments
        return assignments
    }

    fun save(app: String, category: AppCategory): Map<String, AppCategory> = synchronized(this) {
        write(read() + (app to category))
    }

    fun merge(imported: Map<String, AppCategory>) = synchronized(this) {
        if (imported.isNotEmpty()) {
            write(imported + read())
        }
    }
}
