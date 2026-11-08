package dev.usagetime.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.database.MatrixCursor
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.BackupStreamReader
import dev.usagetime.app.data.BackupStreamWriter
import dev.usagetime.app.data.CategoryPreferences
import dev.usagetime.app.data.UsageStore
import dev.usagetime.core.AppCategory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InsightsStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun categoriesUseAnOptionalV3ExtensionAndReadBackWithoutChangingHistoryTables() = runBlocking {
        val output = ByteArrayOutputStream()
        BackupStreamWriter.write(output, mapOf("fixture.category" to AppCategory.STUDY)) { MatrixCursor(arrayOf("id")) }
        val json = JSONObject(output.toString("UTF-8"))
        assertEquals(3, json.getInt("version"))
        val values = mutableMapOf<String, String>()
        BackupStreamReader.read(output.toByteArray().inputStream(), object : BackupStreamReader.Sink {
            override suspend fun row(table: String, row: JSONObject) {
                assertEquals("app_categories", table)
                values[row.getString("app")] = row.getString("category")
            }
            override suspend fun checkpoint() {}
            override suspend fun endArray(table: String) {}
        })
        assertEquals(mapOf("fixture.category" to "STUDY"), values)
    }

    @Test fun categoryImportPreservesExistingAssignmentsAndRejectsInvalidBackup() = runBlocking {
        val name = "safety-test-categories-${UUID.randomUUID()}.db"
        val preferences = CategoryPreferences(context, "usage-categories-$name")
        val store = UsageStore(context, name)
        try {
            preferences.save("fixture.existing", AppCategory.WORK)
            val valid = """{"format":"usagetime-backup","version":3,"app_categories":[{"app":"fixture.existing","category":"GAME"},{"app":"fixture.new","category":"STUDY"}]}"""
            store.importJson(valid)
            assertEquals(AppCategory.WORK, preferences.read()["fixture.existing"])
            assertEquals(AppCategory.STUDY, preferences.read()["fixture.new"])
            val before = preferences.read()
            val invalid = valid.replace("\"version\":3", "\"version\":99").replace("STUDY", "VIDEO")
            assertTrue(runCatching { store.importJson(invalid) }.isFailure)
            assertEquals(before, preferences.read())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun widgetLayoutInflatesAndProviderIsRegistered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val views = RemoteViews(context.packageName, R.layout.usage_widget)
            views.setTextViewText(R.id.widget_total, "0分")
            val rendered = views.apply(context, null)
            assertEquals("0分", rendered.findViewById<TextView>(R.id.widget_total).text.toString())
        }
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, UsageWidgetProvider::class.java)
        assertTrue(manager.installedProviders.any { it.provider == component })
    }
}
