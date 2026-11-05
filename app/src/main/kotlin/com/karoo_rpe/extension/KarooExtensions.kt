package com.karoo_rpe.extension

import com.karoo_rpe.sync.HttpRequest
import com.karoo_rpe.sync.HttpResult
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val id = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { ev: OnStreamState ->
        trySendBlocking(ev.state)
    }
    awaitClose { removeConsumer(id) }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val id = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(id) }
}

/**
 * Convert an ELAPSED_TIME stream from milliseconds to seconds.
 *
 * The Karoo SDK emits ELAPSED_TIME values in **milliseconds** (the SDK sample divides by 1000
 * to obtain seconds). Everything downstream — the coordinators, the warm-up gate, the W'bal dt
 * integration — assumes **seconds**, so this is the single boundary that performs that
 * conversion. Apply it once, to both the real and simulated elapsed flows, right where the flow
 * is selected; non-streaming states pass through untouched.
 */
fun Flow<StreamState>.elapsedSeconds(): Flow<StreamState> = map { state ->
    when (state) {
        is StreamState.Streaming -> {
            val dp = state.dataPoint
            StreamState.Streaming(dp.copy(values = dp.values.mapValues { it.value / 1000.0 }))
        }
        else -> state
    }
}

/**
 * One HTTP request through the Karoo's network connection (Wi-Fi, or the companion phone over
 * Bluetooth). Waits for a connection if there is none. Suspends until the request completes.
 */
suspend fun KarooSystemService.http(request: HttpRequest): HttpResult =
    callbackFlow {
        val id = addConsumer(
            OnHttpResponse.MakeHttpRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = request.body,
                waitForConnection = true,
            ),
            onError = { msg ->
                trySend(HttpResult(0, null, msg))
                close()
            },
        ) { ev: OnHttpResponse ->
            val state = ev.state
            if (state is HttpResponseState.Complete) {
                trySend(
                    HttpResult(
                        status = state.statusCode,
                        body = state.body?.decodeToString(),
                        error = state.error,
                    ),
                )
                close()
            }
        }
        awaitClose { removeConsumer(id) }
    }.first()
