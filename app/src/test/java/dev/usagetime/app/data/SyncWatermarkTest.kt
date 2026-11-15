package dev.usagetime.app.data

import dev.usagetime.core.AppCategory
import dev.usagetime.core.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SyncWatermarkTest {
    @Test
    fun noChangesReturnsMaxValue() {
        val tracker = SyncWatermarkTracker()
        assertEquals(Long.MAX_VALUE, tracker.dirtyFrom(0L))

        tracker.recordIncremental(1_000L)
        tracker.recordIncremental(2_000L)
        assertEquals(Long.MAX_VALUE, tracker.dirtyFrom(2L))
    }

    @Test
    fun incrementalChangesReturnEarliestTimestampSinceSeq() {
        val tracker = SyncWatermarkTracker()
        tracker.recordIncremental(5_000L) // seq = 1
        tracker.recordIncremental(2_000L) // seq = 2
        tracker.recordIncremental(9_000L) // seq = 3

        assertEquals(2_000L, tracker.dirtyFrom(0L))
        assertEquals(2_000L, tracker.dirtyFrom(1L))
        assertEquals(9_000L, tracker.dirtyFrom(2L))
        assertEquals(Long.MAX_VALUE, tracker.dirtyFrom(3L))
    }

    @Test
    fun fullRebuildInvalidatesEarlierSeqsOnly() {
        val tracker = SyncWatermarkTracker()
        tracker.recordIncremental(10_000L) // seq = 1
        tracker.recordFull()               // seq = 2, fullSeq = 2
        tracker.recordIncremental(20_000L) // seq = 3

        assertNull(tracker.dirtyFrom(0L))
        assertNull(tracker.dirtyFrom(1L))
        assertEquals(20_000L, tracker.dirtyFrom(2L))
        assertEquals(Long.MAX_VALUE, tracker.dirtyFrom(3L))
    }

    @Test
    fun ringBufferOverflowReturnsNullForEvictedSeq() {
        val tracker = SyncWatermarkTracker(capacity = 3)
        tracker.recordIncremental(100L) // seq 1 (evicted after seq 4)
        tracker.recordIncremental(200L) // seq 2
        tracker.recordIncremental(300L) // seq 3
        tracker.recordIncremental(400L) // seq 4 -> buffer keeps [2, 3, 4]

        assertNull(tracker.dirtyFrom(0L))
        assertEquals(200L, tracker.dirtyFrom(1L))
        assertEquals(300L, tracker.dirtyFrom(2L))
        assertEquals(400L, tracker.dirtyFrom(3L))
        assertEquals(Long.MAX_VALUE, tracker.dirtyFrom(4L))
    }

    @Test
    fun invalidOrFutureSeqReturnsNull() {
        val tracker = SyncWatermarkTracker()
        tracker.recordIncremental(100L)
        assertNull(tracker.dirtyFrom(-1L))
        assertNull(tracker.dirtyFrom(99L))
    }

    @Test
    fun encodeDecodeRoundTripAndCorruptFallback() {
        val tracker = SyncWatermarkTracker(capacity = 8)
        tracker.recordIncremental(1_500L)
        tracker.bumpLabels()
        tracker.recordIncremental(900L)
        tracker.bumpLabels()

        val encoded = tracker.encode()
        val restored = SyncWatermarkTracker.decode(encoded, capacity = 8)
        assertEquals(2L, restored.seq)
        assertEquals(0L, restored.fullSeq)
        assertEquals(2L, restored.labelsVersion)
        assertEquals(900L, restored.dirtyFrom(0L))
        assertEquals(900L, restored.dirtyFrom(1L))
        assertEquals(Long.MAX_VALUE, restored.dirtyFrom(2L))

        val fallback = SyncWatermarkTracker.decode("{broken json")
        assertEquals(0L, fallback.seq)
        assertEquals(0L, fallback.labelsVersion)
        assertEquals(Long.MAX_VALUE, fallback.dirtyFrom(0L))
    }

    @Test
    fun phoneSyncHelpersSkipBeforeAndDigests() {
        val zone = ZoneId.of("Asia/Shanghai")
        val first = LocalDate.of(2026, 10, 1)
        val today = LocalDate.of(2026, 11, 15)
        val dirtyPast = Dates.midnight(LocalDate.of(2026, 11, 10), zone)

        assertEquals(
            LocalDate.of(2026, 11, 10),
            PhoneSyncHelpers.skipBeforeDate(
                first = first,
                today = today,
                republish = false,
                remoteMissing = false,
                scopeMatches = true,
                categoryMatches = true,
                labelsMatch = true,
                syncedEndDate = today,
                dirtyMillis = dirtyPast,
                zone = zone
            )
        )

        // Day rollover with clean watermark must still re-verify the previous syncedEndDate.
        val yesterday = today.minusDays(1)
        assertEquals(
            yesterday,
            PhoneSyncHelpers.skipBeforeDate(
                first = first,
                today = today,
                republish = false,
                remoteMissing = false,
                scopeMatches = true,
                categoryMatches = true,
                labelsMatch = true,
                syncedEndDate = yesterday,
                dirtyMillis = Long.MAX_VALUE,
                zone = zone
            )
        )

        // Invalidation switches skipBefore back to first.
        assertEquals(
            first,
            PhoneSyncHelpers.skipBeforeDate(
                first = first,
                today = today,
                republish = false,
                remoteMissing = false,
                scopeMatches = true,
                categoryMatches = false,
                labelsMatch = true,
                syncedEndDate = today,
                dirtyMillis = Long.MAX_VALUE,
                zone = zone
            )
        )

        val d1 = PhoneSyncHelpers.categoryDigest(mapOf("b" to AppCategory.WORK, "a" to AppCategory.SOCIAL))
        val d2 = PhoneSyncHelpers.categoryDigest(mapOf("a" to AppCategory.SOCIAL, "b" to AppCategory.WORK))
        val d3 = PhoneSyncHelpers.categoryDigest(mapOf("a" to AppCategory.VIDEO, "b" to AppCategory.WORK))
        assertEquals(d1, d2)
        assertNotEquals(d1, d3)

        val m1 = PhoneSyncHelpers.manifestDigest("id1", "Phone", zone.id, mapOf("2026-11-15" to "h2", "2026-11-14" to "h1"))
        val m2 = PhoneSyncHelpers.manifestDigest("id1", "Phone", zone.id, mapOf("2026-11-14" to "h1", "2026-11-15" to "h2"))
        val m3 = PhoneSyncHelpers.manifestDigest("id1", "PhoneRenamed", zone.id, mapOf("2026-11-14" to "h1", "2026-11-15" to "h2"))
        assertEquals(m1, m2)
        assertNotEquals(m1, m3)

        assertFalse(PhoneSyncHelpers.shouldUploadManifest(republish = false, remoteMissing = false, changedDays = 0, prevManifestMeta = m1, nextManifestMeta = m2))
        assertTrue(PhoneSyncHelpers.shouldUploadManifest(republish = false, remoteMissing = false, changedDays = 0, prevManifestMeta = m1, nextManifestMeta = m3))
        assertTrue(PhoneSyncHelpers.shouldUploadManifest(republish = false, remoteMissing = false, changedDays = 1, prevManifestMeta = m1, nextManifestMeta = m2))
        assertTrue(PhoneSyncHelpers.shouldUploadManifest(republish = false, remoteMissing = true, changedDays = 0, prevManifestMeta = m1, nextManifestMeta = m2))
    }
}
