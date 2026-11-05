package com.karoo_rpe.sync

import com.karoo_rpe.extension.Feel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class IntervalsSyncTest {

    private val rideStart = Instant.parse("2026-11-05T18:22:30Z").toEpochMilli()

    // Shape of a real /athlete/0/activities response (ids/external ids made up).
    private val listJson = """
        [
          {"id":"i2","start_date":"2026-11-05T19:00:48Z","device_name":"HAMMERHEAD Karoo","external_id":"1.activity.bbb"},
          {"id":"i1","start_date":"2026-11-05T18:22:36Z","device_name":"HAMMERHEAD Karoo","external_id":"1.activity.aaa"},
          {"id":"g1","start_date":"2026-11-05T18:22:00Z","device_name":"Garmin Edge 1040","external_id":"12345678901","icu_rpe":7,"feel":3}
        ]
    """.trimIndent()

    @Test
    fun `parses activities and picks the closest Karoo ride, ignoring other devices`() {
        val activities = IntervalsApi.parseActivities(listJson)
        assertEquals(3, activities.size)
        assertEquals("i1", IntervalsApi.pick(activities, rideStart)?.id)
    }

    @Test
    fun `no match outside tolerance`() {
        val activities = IntervalsApi.parseActivities(listJson)
        assertNull(IntervalsApi.pick(activities, rideStart - 60 * 60_000))
    }

    @Test
    fun `update body uses intervals scales`() {
        assertEquals("""{"icu_rpe":7,"feel":2}""", IntervalsApi.updateBody(7, Feel.GOOD))
        assertEquals("""{"feel":5}""", IntervalsApi.updateBody(null, Feel.WEAK))
        assertEquals("""{"icu_rpe":3}""", IntervalsApi.updateBody(3, null))
    }

    @Test
    fun `feel maps Strong to 1 and Weak to 5`() {
        assertEquals(listOf(1, 2, 3, 4, 5), Feel.entries.reversed().map { it.intervalsValue() })
    }

    @Test
    fun `basic auth uses API_KEY username`() {
        val header = IntervalsApi.headers("secret")["Authorization"]!!
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals("API_KEY:secret", decoded)
    }

    @Test
    fun `list request asks from the day before the ride`() {
        val url = IntervalsApi.listRequest("k", rideStart).url
        assertTrue(url, url.contains("athlete/0/activities?oldest=2026-11-04"))
    }

    private fun pending(rpe: Int? = 6, feel: Feel? = Feel.NORMAL) =
        PendingSync(rpe, feel, rideStart, endedAtMs = rideStart + 60_000)

    @Test
    fun `attempt lists then updates the matched activity`() = runTest {
        val calls = mutableListOf<HttpRequest>()
        val syncer = IntervalsSyncer("k") { req ->
            calls += req
            if (req.method == "GET") HttpResult(200, listJson) else HttpResult(200, "{}")
        }
        assertEquals(SyncOutcome.Done("i1"), syncer.attempt(pending()))
        assertEquals("PUT", calls[1].method)
        assertTrue(calls[1].url.endsWith("/activity/i1"))
        assertEquals("""{"icu_rpe":6,"feel":3}""", String(calls[1].body!!))
    }

    @Test
    fun `not uploaded yet when no activity matches`() = runTest {
        val syncer = IntervalsSyncer("k") { HttpResult(200, "[]") }
        assertEquals(SyncOutcome.NotUploadedYet, syncer.attempt(pending()))
    }

    @Test
    fun `rejected key is fatal, network errors retry`() = runTest {
        assertTrue(IntervalsSyncer("k") { HttpResult(401, null) }.attempt(pending()) is SyncOutcome.Fatal)
        assertTrue(IntervalsSyncer("k") { HttpResult(0, null, "offline") }.attempt(pending()) is SyncOutcome.Retry)
        assertTrue(IntervalsSyncer("k") { HttpResult(503, null) }.attempt(pending()) is SyncOutcome.Retry)
    }

    @Test
    fun `permanent client errors are fatal, throttling and timeouts retry`() = runTest {
        assertTrue(IntervalsSyncer("k") { HttpResult(404, null) }.attempt(pending()) is SyncOutcome.Fatal)
        val putRejected = IntervalsSyncer("k") { req ->
            if (req.method == "GET") HttpResult(200, listJson) else HttpResult(422, null)
        }
        assertTrue(putRejected.attempt(pending()) is SyncOutcome.Fatal)
        assertTrue(IntervalsSyncer("k") { HttpResult(429, null) }.attempt(pending()) is SyncOutcome.Retry)
        assertTrue(IntervalsSyncer("k") { HttpResult(408, null) }.attempt(pending()) is SyncOutcome.Retry)
    }

    @Test
    fun `runSync retries with backoff until the activity appears`() = runTest {
        var listCalls = 0
        val syncer = IntervalsSyncer("k") { req ->
            if (req.method == "GET") {
                listCalls++
                HttpResult(200, if (listCalls < 3) "[]" else listJson)
            } else {
                HttpResult(200, "{}")
            }
        }
        val outcome = runSync(pending(), syncer, now = { rideStart + currentTime })
        assertEquals(SyncOutcome.Done("i1"), outcome)
        assertEquals(3, listCalls)
        // 30 s + 60 s + 120 s of backoff.
        assertEquals(210_000L, currentTime)
    }

    @Test
    fun `runSync gives up after the deadline`() = runTest {
        val syncer = IntervalsSyncer("k") { HttpResult(200, "[]") }
        val outcome = runSync(pending(), syncer, now = { rideStart + currentTime }, maxWaitMs = 30 * 60_000)
        assertTrue(outcome is SyncOutcome.Fatal)
    }
}
