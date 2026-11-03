package dev.usagetime.app.data

internal class BackupImportBatchScheduler<E, S, I>(
    private val writeEvents: suspend (List<E>) -> Unit,
    private val writeSlices: suspend (List<S>) -> Unit,
    private val writeIntervals: suspend (List<I>) -> Unit,
    private val ensureWriteSpace: suspend () -> Unit,
) {
    private val events = ArrayList<E>()
    private val slices = ArrayList<S>()
    private val intervals = ArrayList<I>()
    private var batchBytes = 0L

    fun addEvent(value: E, byteCost: Long) {
        events += value
        batchBytes += byteCost
    }

    fun addSlice(value: S, byteCost: Long) {
        slices += value
        batchBytes += byteCost
    }

    fun addInterval(value: I, byteCost: Long) {
        intervals += value
        batchBytes += byteCost
    }

    suspend fun finishRow() {
        if (events.size + slices.size + intervals.size >= 1000 || batchBytes >= 1_048_576L) {
            flushBatches()
            ensureWriteSpace()
        }
    }

    suspend fun flushBatches() {
        if (events.isNotEmpty()) {
            writeEvents(events)
            events.clear()
        }
        if (slices.isNotEmpty()) {
            writeSlices(slices)
            slices.clear()
        }
        if (intervals.isNotEmpty()) {
            writeIntervals(intervals)
            intervals.clear()
        }
        batchBytes = 0L
    }
}
