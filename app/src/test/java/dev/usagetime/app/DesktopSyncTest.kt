package dev.usagetime.app

import dev.usagetime.app.sync.DesktopSync
import dev.usagetime.app.sync.PhoneCrypto
import dev.usagetime.app.sync.PhoneSyncSettings
import dev.usagetime.app.sync.SyncSupport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSyncTest {
    private fun payload(recorded: Long = 900_000, declared: Long = recorded): ByteArray = JSONObject()
        .put("format", "hindsight.desktop.day")
        .put("version", 1)
        .put("deviceId", "11111111-1111-4111-8111-111111111111")
        .put("deviceName", "测试电脑")
        .put("platform", "win")
        .put("zone", "+08:00")
        .put("date", "2026-11-09")
        .put("recordedMillis", declared)
        .put("firstMillis", 1_794_157_200_000L)
        .put("lastMillis", 1_794_160_800_000L)
        .put("updatedAt", "2026-11-09T12:00:00Z")
        .put(
            "apps",
            JSONArray().put(
                JSONObject()
                    .put("app", "Codex")
                    .put("label", "Codex")
                    .put("category", "code")
                    .put("categoryLabel", "写代码")
                    .put("color", "#a78bfa")
                    .put("millis", recorded)
                    .put("sessions", 3)
            )
        )
        .put(
            "categories",
            JSONArray().put(
                JSONObject()
                    .put("category", "code")
                    .put("label", "写代码")
                    .put("color", "#a78bfa")
                    .put("millis", recorded)
            )
        )
        .toString()
        .toByteArray(Charsets.UTF_8)

    @Test
    fun parsesPublishedDesktopDay() {
        val day = DesktopSync.parseDay(payload())
        assertEquals("2026-11-09", day.date)
        assertEquals(900_000, day.recordedMillis)
        assertEquals(1, day.apps.size)
        assertEquals("写代码", day.apps.first().categoryLabel)
        assertEquals(900_000, day.categories.single().millis)
    }

    @Test
    fun rejectsTotalsThatDoNotMatchApps() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.parseDay(payload(declared = 900_001))
        }
    }

    @Test
    fun rejectsOverflowingAppAndCategoryTotals() {
        listOf("apps", "categories").forEach { field ->
            val json = JSONObject(String(payload(recorded = 0), Charsets.UTF_8))
            val template = json.getJSONArray(field).getJSONObject(0)
            val entries = JSONArray()
            listOf(Long.MAX_VALUE, Long.MAX_VALUE, 2L).forEach { millis ->
                entries.put(JSONObject(template.toString()).put("millis", millis))
            }
            json.put(field, entries)
            assertThrows(IllegalArgumentException::class.java) {
                DesktopSync.parseDay(json.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun rejectsMalformedIdentityAndDates() {
        assertTrue(DesktopSync.validDevice("11111111-1111-4111-8111-111111111111"))
        assertFalse(DesktopSync.validDevice("../other"))
        assertFalse(DesktopSync.validDevice("11111111-1111-4111-8111-11111111111A"))
        assertTrue(DesktopSync.validDate("2026-11-09"))
        assertFalse(DesktopSync.validDate("2026-02-30"))
    }

    @Test
    fun onlyAcceptsHttpsEndpointWithSeparateCredentials() {
        assertEquals(
            "https://dav.example.com/dav/hindsight-desktop-v1/",
            DesktopSync.root("https://dav.example.com/dav").toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.root("http://dav.example.com/dav/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.root(
                java.net.URI("https", "fixture:fixture", "dav.example.com", -1, "/dav/", null, null).toString()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.root("https://dav.example.com/dav/?token=secret")
        }
    }

    @Test
    fun decryptsDesktopVectorProducedByHindsight() {
        val bytes = java.util.Base64.getDecoder()
            .decode("AwMDAwMDAwMDAwMDQZvQaC5HLnwreMn9wLwOEzuZMFhSuOg=")
        val clear = PhoneCrypto.decrypt(
            ByteArray(32) { 7 },
            "desktop/day",
            bytes,
            "hindsight-desktop-sync-v1"
        )
        assertEquals("desktop", String(clear, Charsets.UTF_8))
    }

    @Test
    fun readsOnlyDesktopManifestsFromDavListing() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/hindsight-desktop-v1/</d:href></d:response>
              <d:response><d:href>/dav/hindsight-desktop-v1/11111111-1111-4111-8111-111111111111.dhmanifest</d:href></d:response>
              <d:response><d:href>https://dav.example.com/dav/hindsight-desktop-v1/22222222-2222-4222-8222-222222222222.dhmanifest</d:href></d:response>
              <d:response><d:href>/dav/hindsight-desktop-v1/11111111-1111-4111-8111-111111111111.dhmanifest</d:href></d:response>
              <d:response><d:href>/dav/hindsight-desktop-v1/33333333-3333-4333-8333-333333333333.utmanifest</d:href></d:response>
            </d:multistatus>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                "11111111-1111-4111-8111-111111111111.dhmanifest",
                "22222222-2222-4222-8222-222222222222.dhmanifest",
            ),
            DesktopSync.parseHrefs(xml)
        )
    }

    @Test
    fun cachedDayVerificationSupportsResumablePullsAndRejectsTampering() {
        val bytes = payload()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val temp = kotlin.io.path.createTempFile("dhday-test-", ".dhday").toFile()
        try {
            temp.writeBytes(bytes)
            assertTrue(
                DesktopSync.isCachedDayValid(
                    temp,
                    "11111111-1111-4111-8111-111111111111",
                    "2026-11-09",
                    hash
                )
            )
            assertFalse(
                DesktopSync.isCachedDayValid(
                    temp,
                    "11111111-1111-4111-8111-111111111111",
                    "2026-11-10",
                    hash
                )
            )
            assertFalse(
                DesktopSync.isCachedDayValid(
                    temp,
                    "11111111-1111-4111-8111-111111111111",
                    "2026-11-09",
                    "0".repeat(64)
                )
            )
            temp.writeBytes(payload(declared = 900_001))
            val corruptHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
            assertFalse(
                DesktopSync.isCachedDayValid(
                    temp,
                    "11111111-1111-4111-8111-111111111111",
                    "2026-11-09",
                    corruptHash
                )
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun syncSupportUnifiesRootValidationAndRejectsInvalidNamespaces() {
        assertEquals(
            "https://dav.example.com/dav/usagetime-v1/",
            PhoneSyncSettings.rootUrl("https://dav.example.com/dav///").toString()
        )
        assertEquals(
            "https://dav.example.com/dav/hindsight-desktop-v1/",
            SyncSupport.resolveWebDavRoot("https://dav.example.com/dav", DesktopSync.NAMESPACE).toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            SyncSupport.resolveWebDavRoot("https://dav.example.com/dav#frag", "usagetime-v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncSupport.resolveWebDavRoot("https://dav.example.com/dav", "///")
        }
    }

    @Test
    fun parseHrefsBlocksDoctypeAndHandlesQueryAndBlankEntries() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/11111111-1111-4111-8111-111111111111.dhmanifest</d:href></d:response>
            </d:multistatus>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertThrows(Exception::class.java) { DesktopSync.parseHrefs(xxe) }

        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>   </d:href></d:response>
              <d:response><d:href>https://dav.example.com/dav/hindsight-desktop-v1/11111111-1111-4111-8111-111111111111.dhmanifest?v=2#top</d:href></d:response>
            </d:multistatus>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf("11111111-1111-4111-8111-111111111111.dhmanifest"),
            DesktopSync.parseHrefs(xml)
        )
    }

    @Test
    fun parseDaySortsEntriesAppliesFallbacksAndRejectsInvalidBounds() {
        val raw = JSONObject(String(payload(recorded = 0), Charsets.UTF_8))
            .put(
                "apps",
                JSONArray()
                    .put(JSONObject().put("app", "Small").put("label", "").put("category", "").put("categoryLabel", "").put("millis", 100L))
                    .put(JSONObject().put("app", "Large").put("label", "大图").put("category", "work").put("categoryLabel", "工作").put("millis", 300L))
            )
            .put(
                "categories",
                JSONArray()
                    .put(JSONObject().put("category", "other").put("label", "").put("millis", 100L))
                    .put(JSONObject().put("category", "work").put("label", "工作").put("millis", 300L))
            )
            .put("recordedMillis", 400L)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val parsed = DesktopSync.parseDay(raw)
        assertEquals(listOf("Large", "Small"), parsed.apps.map { it.app })
        assertEquals("Small", parsed.apps.last().label)
        assertEquals("其他", parsed.apps.last().categoryLabel)
        assertEquals(listOf("work", "other"), parsed.categories.map { it.category })
        assertEquals("other", parsed.categories.last().label)

        val mismatchedCategory = JSONObject(String(payload(recorded = 100L), Charsets.UTF_8)).apply {
            getJSONArray("categories").getJSONObject(0).put("millis", 99L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.parseDay(mismatchedCategory.toString().toByteArray(Charsets.UTF_8))
        }

        val overMaxDay = JSONObject(String(payload(recorded = 26L * 3_600_000 + 1), Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.parseDay(overMaxDay.toString().toByteArray(Charsets.UTF_8))
        }

        val wrongVersion = JSONObject(String(payload(), Charsets.UTF_8)).put("version", 2)
        assertThrows(IllegalArgumentException::class.java) {
            DesktopSync.parseDay(wrongVersion.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
