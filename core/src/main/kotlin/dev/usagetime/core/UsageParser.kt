package dev.usagetime.core

/** Single-owner foreground accounting; late pauses from the previous activity cannot close its successor. */
object UsageParser {
    fun parse(events: List<Event>, window: Window, excluded: Set<String> = emptySet(), initial: CollectorState = CollectorState()): ParsedUsage {
        val out = mutableListOf<Interval>()
        var state = initial.copy(since = maxOf(window.start, initial.since))
        fun close(at: Long) {
            val end = at.coerceIn(window.start, window.end)
            if (!state.blocked && state.app.isNotBlank() && state.app !in excluded && end > state.since) out += Interval(state.app, state.since, end)
            state = state.copy(app = "", activity = "", since = end)
        }
        // Stable order retains platform ordering for activity handovers sharing a timestamp.
        events.sortedBy { it.time }.filter { it.time >= window.start && it.time < window.end }.forEach { e ->
            when (e.kind) {
                EventKind.RESUME -> {
                    close(e.time)
                    // Keep a pending owner when Android resumes an activity before reporting keyguard hidden.
                    state = state.copy(app = e.app, activity = e.activity, since = e.time)
                }
                EventKind.PAUSE, EventKind.STOP -> if (state.app == e.app && (e.activity.isEmpty() || state.activity.isEmpty() || state.activity == e.activity)) close(e.time)
                EventKind.SCREEN_OFF, EventKind.LOCK, EventKind.SHUTDOWN, EventKind.STARTUP -> { close(e.time); state = state.copy(blocked = true) }
                EventKind.UNLOCK -> state = state.copy(blocked = false, since = if (state.blocked) e.time else state.since)
                EventKind.SCREEN_ON -> Unit // A lit lock screen does not imply unlocked use.
            }
        }
        val open = state
        close(window.end)
        return ParsedUsage(normalize(out), open)
    }

    fun normalize(input: List<Interval>): List<Interval> {
        // Merge touching/overlapping intervals of an app, retaining cross-app exclusivity from the parser.
        return input.groupBy { it.app }.flatMap { (_, entries) ->
            val merged = mutableListOf<Interval>()
            entries.sortedBy { it.start }.forEach { item ->
                val last = merged.lastOrNull()
                if (last != null && item.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, item.end)) else merged += item
            }
            merged
        }.sortedBy { it.start }
    }
}
