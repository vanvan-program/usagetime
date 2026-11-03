package dev.usagetime.app.data

/** Bounded platform reads; the caller owns transactions, metadata and replay. */
internal object RawEventBatchCollector {
    suspend fun <T> collect(
        start: Long,
        end: Long,
        read: (Long, Long) -> Sequence<T>,
        write: suspend (List<T>) -> List<Long>,
        onRow: (T) -> Unit,
        onInserted: (T) -> Unit,
        check: suspend () -> Unit
    ) {
        var from = start
        while (from < end) {
            check()
            val until = minOf(end, from + 6 * 3_600_000L)
            val batch = ArrayList<T>(1000)
            suspend fun flush() {
                if (batch.isEmpty()) return
                val inserted = write(batch)
                inserted.forEachIndexed { index, id ->
                    val row = batch[index]
                    if (id != -1L) onInserted(row)
                }
                batch.clear()
                check()
            }
            for (row in read(from, until)) {
                batch += row
                onRow(row)
                if (batch.size >= 1000) flush()
            }
            flush()
            from = until
        }
    }
}
