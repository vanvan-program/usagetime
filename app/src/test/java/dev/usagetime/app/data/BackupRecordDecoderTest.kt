package dev.usagetime.app.data

import dev.usagetime.core.Evidence
import dev.usagetime.core.Signal
import dev.usagetime.core.UsageMetric
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupRecordDecoderTest {
    private val decoder = BackupRecordDecoder(10_000)
    private fun interval() = JSONObject("""{"app":"fixture","start":100,"end":200}""")
    private fun event() = JSONObject("""{"id":"event","time":100,"signal":"RESUME","app":"fixture","activity":"Main","instance":null,"source":"USAGE_EVENTS","zone":"UTC"}""")
    private fun slice() = JSONObject("""{"id":"slice","metric":"PRIMARY","app":"fixture","start":100,"end":200,"evidence":"CONFIRMED","reason":"focus","sources":"OWN_WINDOW","refs":"event"}""")

    @Test fun v1IntervalKeepsAllFields() {
        assertEquals(SavedInterval("fixture", 100, 200), decoder.interval(interval()))
    }

    @Test fun intervalAllowsZeroAndFrozenUpperBoundary() {
        assertEquals(SavedInterval("fixture", 0, 10_000),
            decoder.interval(interval().put("start", 0).put("end", 10_000)))
    }

    @Test fun invalidIntervalRangesKeepOriginalMessage() {
        for ((start, end) in listOf(-1L to 1L, 100L to 100L, 200L to 100L, 0L to 10_001L)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                decoder.interval(interval().put("start", start).put("end", end))
            }
            assertEquals("备份区间无效", error.message)
        }
    }

    @Test fun blankAndOversizedPackagesRemainRejected() {
        for (pkg in listOf("", " \t", "x".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) {
                decoder.interval(interval().put("app", pkg))
            }
        }
        assertEquals(255, decoder.interval(interval().put("app", "x".repeat(255))).app.length)
    }

    @Test fun missingRequiredIntervalFieldIsRejected() {
        val row = interval().apply { remove("end") }
        assertThrows(JSONException::class.java) { decoder.interval(row) }
    }

    @Test fun coveragePreservesBothReliableValues() {
        for (reliable in listOf(false, true)) {
            assertEquals(SavedCoverage(100, 200, reliable),
                decoder.coverage(interval().put("reliable", reliable)))
        }
    }

    @Test fun coverageUsesSameRangeValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            decoder.coverage(interval().put("end", 10_001).put("reliable", true))
        }
    }

    @Test fun appLabelRetainsExistingUtf16Truncation() {
        val label = "用时🙂".repeat(40)
        assertEquals(SavedApp("fixture", label.take(120)),
            decoder.app(JSONObject().put("app", "fixture").put("label", label)))
    }

    @Test fun blankAppPackageRemainsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            decoder.app(JSONObject().put("app", " ").put("label", "fixture"))
        }
    }

    @Test fun v2EventDefaultsAndEveryFieldArePreserved() {
        val expected = StoredEvent("archive:event", 100, "RESUME", "fixture", "Main",
            null, "LEGACY_IMPORT", "UTC", DualCodec.importOrigin("USAGE_EVENTS", "LOCAL"), null)
        assertEquals(expected, decoder.event(event()))
    }

    @Test fun v3EventKeepsInstanceRawTypeAndOriginalOrigin() {
        val row = event().put("instance", "instance").put("rawType", 7).put("origin", "original")
        val value = decoder.event(row)
        assertEquals("instance", value.instance)
        assertEquals(7, value.rawType)
        assertEquals(DualCodec.importOrigin("USAGE_EVENTS", "original"), value.origin)
    }

    @Test fun eventTimeUsesFrozenBounds() {
        for (time in listOf(0L, 10_000L)) {
            assertEquals(time, decoder.event(event().put("time", time)).time)
        }
        for (time in listOf(-1L, 10_001L)) {
            assertThrows(IllegalArgumentException::class.java) { decoder.event(event().put("time", time)) }
        }
    }

    @Test fun invalidSignalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { decoder.event(event().put("signal", "unsupported")) }
    }

    @Test fun allSupportedSignalsRemainImportable() {
        for (signal in Signal.entries) {
            assertEquals(signal.name, decoder.event(event().put("signal", signal.name)).signal)
        }
    }

    @Test fun confirmedSliceIsDowngradedWithAuditMetadata() {
        val expected = StoredSlice("archive:slice", "PRIMARY", "fixture", 100, 200, "INFERRED",
            DualCodec.importReason("focus", "CONFIRMED", "OWN_WINDOW"),
            "LEGACY_IMPORT", "archive:event", false, 0)
        assertEquals(expected, decoder.slice(slice()))
    }

    @Test fun evidenceAndMetricMatrixPreservesDowngradeRules() {
        for (metric in UsageMetric.entries) for (grade in Evidence.entries) {
            val value = decoder.slice(slice().put("metric", metric.name).put("evidence", grade.name))
            assertEquals(metric.name, value.metric)
            assertEquals(if (grade == Evidence.CONFIRMED || grade == Evidence.INFERRED) "INFERRED" else grade.name,
                value.evidence)
            assertEquals(0, value.algorithm)
            assertEquals("LEGACY_IMPORT", value.sources)
        }
    }

    @Test fun nullableSliceAppAndProvisionalFlagAreRetained() {
        val value = decoder.slice(slice().put("app", JSONObject.NULL).put("provisional", true))
        assertNull(value.app)
        assertTrue(value.provisional)
    }

    @Test fun invalidSliceRangeAndPackageAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { decoder.slice(slice().put("end", 10_001)) }
        assertThrows(IllegalArgumentException::class.java) { decoder.slice(slice().put("app", "x".repeat(256))) }
    }

    @Test fun invalidMetricAndEvidenceRemainRejected() {
        assertThrows(IllegalArgumentException::class.java) { decoder.slice(slice().put("metric", "unsupported")) }
        assertThrows(IllegalArgumentException::class.java) { decoder.slice(slice().put("evidence", "unsupported")) }
    }

    @Test fun referenceRewritingKeepsOrderDuplicatesAndExistingPrefix() {
        val row = slice().put("refs", "event,,archive:old, ,event")
        assertEquals("archive:event,archive:old,archive:event", decoder.slice(row).refs)
        assertEquals("", decoder.slice(slice().apply { remove("refs") }).refs)
    }

    @Test fun importedIdsAndProvenanceStayStableAcrossRepeatedRestore() {
        var rawEvent = event()
        var rawSlice = slice()
        val firstEvent = decoder.event(rawEvent)
        val firstSlice = decoder.slice(rawSlice)
        repeat(50) {
            val decodedEvent = decoder.event(rawEvent)
            val decodedSlice = decoder.slice(rawSlice)
            assertEquals(firstEvent, decodedEvent)
            assertEquals(firstSlice, decodedSlice)
            rawEvent = rawEvent.put("id", decodedEvent.id).put("source", decodedEvent.source).put("origin", decodedEvent.origin)
            rawSlice = rawSlice.put("id", decodedSlice.id).put("evidence", decodedSlice.evidence)
                .put("reason", decodedSlice.reason).put("sources", decodedSlice.sources).put("refs", decodedSlice.refs)
        }
    }

    @Test fun unknownScalarFieldsDoNotAffectDecodedValue() {
        val original = decoder.slice(slice())
        assertEquals(original, decoder.slice(slice().put("futureField", "ignored").put("futureFlag", true)))
    }

    @Test fun decodingDoesNotMutateInputRows() {
        val rows = listOf(interval(), interval().put("reliable", true),
            JSONObject().put("app", "fixture").put("label", "label"), event(), slice())
        val before = rows.map { it.toString() }
        decoder.interval(rows[0]); decoder.coverage(rows[1]); decoder.app(rows[2])
        decoder.event(rows[3]); decoder.slice(rows[4])
        assertEquals(before, rows.map { it.toString() })
    }
}
