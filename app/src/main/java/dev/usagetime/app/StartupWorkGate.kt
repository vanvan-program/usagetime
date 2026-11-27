package dev.usagetime.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Lets a cold Activity read its first report before a background worker takes the store mutex. */
internal class StartupWorkGate(
    private val elapsed: () -> Long,
    private val activityGraceMillis: Long = 250,
    private val reportTimeoutMillis: Long = 2_000
) {
    private val startedAt = elapsed()
    private val reportDrawn = MutableStateFlow<Boolean?>(null)

    fun activityStarted() {
        reportDrawn.compareAndSet(null, false)
    }

    fun firstReportDrawn() {
        reportDrawn.compareAndSet(false, true)
    }

    suspend fun awaitReportIfLaunching() {
        if (reportDrawn.value == null) {
            // A worker can enter immediately after Application.onCreate, before the Activity exists.
            val remainingGrace = activityGraceMillis - (elapsed() - startedAt)
            if (remainingGrace > 0) withTimeoutOrNull(remainingGrace) { reportDrawn.filterNotNull().first() }
        }
        if (reportDrawn.value == false) {
            // A background-only launch or a window that never draws must still collect normally.
            withTimeoutOrNull(reportTimeoutMillis) { reportDrawn.first { it == true } }
        }
    }
}
