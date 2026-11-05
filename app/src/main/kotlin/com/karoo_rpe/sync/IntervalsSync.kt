package com.karoo_rpe.sync

import com.karoo_rpe.extension.Feel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * Workaround for Karoo firmware not writing native FIT RPE/Feel (karoo-ext issue #94): after
 * the ride is uploaded, set `icu_rpe` / `feel` on the matching intervals.icu activity.
 *
 * Pure logic with an injected HTTP function, so it's JVM-testable. The extension supplies an
 * HTTP function backed by the Karoo's own connection (Wi-Fi or the phone over Bluetooth).
 */

/** A finished ride's rating waiting to be sent. [rideStartMs] is when recording started. */
data class PendingSync(
    val rpe: Int?,
    val feel: Feel?,
    val rideStartMs: Long,
    val endedAtMs: Long,
)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

/** [status] 0 means no HTTP response (network/transport error, see [error]). */
data class HttpResult(val status: Int, val body: String?, val error: String? = null)

data class IntervalsActivity(
    val id: String,
    val startMs: Long,
    val deviceName: String?,
    val externalId: String?,
) {
    /** Karoo uploads have device_name "HAMMERHEAD Karoo" and external_id "<user>.activity.<rideId>". */
    val isKaroo: Boolean
        get() = deviceName?.contains("karoo", ignoreCase = true) == true ||
            externalId?.contains(".activity.") == true
}

sealed interface SyncOutcome {
    data class Done(val activityId: String) : SyncOutcome
    /** The activity isn't on intervals.icu yet (upload pending). Try again later. */
    data object NotUploadedYet : SyncOutcome
    data class Retry(val reason: String) : SyncOutcome
    data class Fatal(val reason: String) : SyncOutcome
}

/** intervals.icu Feel: 1 = Strong … 5 = Weak. */
fun Feel.intervalsValue(): Int = when (this) {
    Feel.STRONG -> 1
    Feel.GOOD -> 2
    Feel.NORMAL -> 3
    Feel.POOR -> 4
    Feel.WEAK -> 5
}

object IntervalsApi {
    private const val BASE = "https://intervals.icu/api/v1"
    const val MATCH_TOLERANCE_MS = 5 * 60_000L
    private val json = Json { ignoreUnknownKeys = true }

    fun headers(apiKey: String): Map<String, String> = mapOf(
        // Personal API key: Basic auth with username "API_KEY".
        "Authorization" to "Basic " + Base64.getEncoder()
            .encodeToString("API_KEY:$apiKey".toByteArray(Charsets.UTF_8)),
        "Accept" to "application/json",
        "User-Agent" to "karoo-rpe",
    )

    /** Activities since the day before the ride (UTC), athlete "0" = the key's owner. */
    fun listRequest(apiKey: String, rideStartMs: Long): HttpRequest {
        val oldest = Instant.ofEpochMilli(rideStartMs).atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return HttpRequest(
            method = "GET",
            url = "$BASE/athlete/0/activities?oldest=$oldest&limit=30" +
                "&fields=id,start_date,device_name,external_id",
            headers = headers(apiKey),
        )
    }

    fun updateBody(rpe: Int?, feel: Feel?): String = buildJsonObject {
        rpe?.let { put("icu_rpe", it) }
        feel?.let { put("feel", it.intervalsValue()) }
    }.toString()

    fun updateRequest(apiKey: String, activityId: String, pending: PendingSync): HttpRequest =
        HttpRequest(
            method = "PUT",
            url = "$BASE/activity/$activityId",
            headers = headers(apiKey) + ("Content-Type" to "application/json"),
            body = updateBody(pending.rpe, pending.feel).toByteArray(Charsets.UTF_8),
        )

    fun parseActivities(body: String): List<IntervalsActivity> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val start = obj["start_date"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            IntervalsActivity(
                id = id,
                startMs = start,
                deviceName = obj["device_name"]?.jsonPrimitive?.contentOrNull,
                externalId = obj["external_id"]?.jsonPrimitive?.contentOrNull,
            )
        }

    /** The Karoo activity whose start is closest to [rideStartMs], within the tolerance. */
    fun pick(activities: List<IntervalsActivity>, rideStartMs: Long): IntervalsActivity? =
        activities
            .filter { it.isKaroo && abs(it.startMs - rideStartMs) <= MATCH_TOLERANCE_MS }
            .minByOrNull { abs(it.startMs - rideStartMs) }
}

class IntervalsSyncer(
    private val apiKey: String,
    private val http: suspend (HttpRequest) -> HttpResult,
) {
    suspend fun attempt(pending: PendingSync): SyncOutcome {
        if (pending.rpe == null && pending.feel == null) return SyncOutcome.Fatal("nothing to send")

        val list = http(IntervalsApi.listRequest(apiKey, pending.rideStartMs))
        classifyError(list)?.let { return it }
        val activities = runCatching { IntervalsApi.parseActivities(list.body.orEmpty()) }
            .getOrElse { return SyncOutcome.Retry("unreadable activity list") }
        val match = IntervalsApi.pick(activities, pending.rideStartMs) ?: return SyncOutcome.NotUploadedYet

        val put = http(IntervalsApi.updateRequest(apiKey, match.id, pending))
        classifyError(put)?.let { return it }
        return SyncOutcome.Done(match.id)
    }

    private fun classifyError(r: HttpResult): SyncOutcome? = when {
        r.status in 200..299 -> null
        r.status == 401 || r.status == 403 -> SyncOutcome.Fatal("intervals.icu rejected the API key (HTTP ${r.status})")
        r.status == 0 -> SyncOutcome.Retry(r.error ?: "no connection")
        else -> SyncOutcome.Retry("HTTP ${r.status}")
    }
}

/**
 * Retries until the activity shows up and is updated, or [maxWaitMs] after the ride ended.
 * Backoff starts at [firstDelayMs] and doubles up to [maxDelayMs]. The deadline is based on
 * [PendingSync.endedAtMs], so it carries over if the service restarts and resumes.
 */
suspend fun runSync(
    pending: PendingSync,
    syncer: IntervalsSyncer,
    now: () -> Long,
    firstDelayMs: Long = 30_000,
    maxDelayMs: Long = 10 * 60_000,
    maxWaitMs: Long = 12 * 60 * 60_000L,
): SyncOutcome {
    var wait = firstDelayMs
    while (true) {
        delay(wait)
        when (val outcome = syncer.attempt(pending)) {
            is SyncOutcome.Done, is SyncOutcome.Fatal -> return outcome
            else -> if (now() - pending.endedAtMs > maxWaitMs) {
                return SyncOutcome.Fatal("activity not found on intervals.icu")
            }
        }
        wait = min(wait * 2, maxDelayMs)
    }
}
