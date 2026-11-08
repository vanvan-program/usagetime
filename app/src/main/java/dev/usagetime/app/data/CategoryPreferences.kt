package dev.usagetime.app.data

import android.content.Context
import dev.usagetime.core.AppCategory
import org.json.JSONObject

class CategoryPreferences(context: Context, name: String = "usage-categories") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun read(): Map<String, AppCategory> {
        val json = runCatching { JSONObject(preferences.getString("assignments", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { AppCategory.fromKey(json.optString(it)) }
    }

    private fun write(assignments: Map<String, AppCategory>): Map<String, AppCategory> {
        val json = JSONObject()
        assignments.forEach { (key, value) -> json.put(key, value.name) }
        check(preferences.edit().putString("assignments", json.toString()).commit()) { "无法保存分类设置" }
        return assignments
    }

    fun save(app: String, category: AppCategory): Map<String, AppCategory> =
        write(read() + (app to category))

    fun merge(imported: Map<String, AppCategory>) {
        if (imported.isNotEmpty()) write(imported + read())
    }
}
