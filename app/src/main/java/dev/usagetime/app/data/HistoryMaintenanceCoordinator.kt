package dev.usagetime.app.data

import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** Coordinates maintenance while the caller owns the storage lock and transactions. */
internal object HistoryMaintenanceCoordinator {
    data class State(val elapsed: Long, val boot: Int, val now: Long, val zone: ZoneId)
    suspend fun <T : Any> run(
        prepare: () -> T?,
        archive: suspend (T) -> Unit,
        compact: suspend (T) -> Unit,
        deferred: suspend (T, Exception) -> Unit,
        finishMaintenance: suspend (T) -> Unit,
        recordMaintenance: (T) -> Unit,
        snapshot: suspend () -> Unit,
        warning: (Exception) -> Unit
    ) {
        try {
            prepare()?.let { state ->
                archive(state)
                try {
                    compact(state)
                } catch (e: IllegalArgumentException) {
                    deferred(state, e)
                } catch (e: IllegalStateException) {
                    // CancellationException is an IllegalStateException on the JVM.
                    // The old Store handler also logged it here before cancellation
                    // reached a subsequent suspend operation.
                    deferred(state, e)
                }
                finishMaintenance(state)
                recordMaintenance(state)
            }
            snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warning(e)
        }
    }
}
