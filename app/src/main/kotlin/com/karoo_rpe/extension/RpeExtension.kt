package com.karoo_rpe.extension

import com.karoo_rpe.BuildConfig
import com.karoo_rpe.RateRideActivity
import com.karoo_rpe.data.RideRatingRepository
import com.karoo_rpe.sync.IntervalsSyncer
import com.karoo_rpe.sync.PendingSync
import com.karoo_rpe.sync.SyncOutcome
import com.karoo_rpe.sync.runSync
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Captures a ride rating (RPE + Feel), writes it into the FIT SessionMesg as developer fields
 * `workout_rpe` / `workout_feel`, and, after the ride, sets it on the uploaded intervals.icu
 * activity via the API (workaround for karoo-ext issue #94). Thin wiring: the logic lives in
 * [RideRatingCoordinator] and [IntervalsSyncer].
 */
class RpeExtension : KarooExtension(EXTENSION_ID, "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var repository: RideRatingRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types: List<DataTypeImpl> by lazy {
        listOf(RideRatingDataType(repository, EXTENSION_ID))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        repository = RideRatingRepository(applicationContext)
        karooSystem.connect()

        val coordinator = coordinator()
        scope.launch { coordinator.rideActiveEvents.collect { repository.markRideStarted(System.currentTimeMillis()) } }
        scope.launch { coordinator.resetEvents.collect { repository.finishRide(System.currentTimeMillis()) } }
        scope.launch { repository.pendingFlow.collectLatest { it?.let { pending -> syncToIntervals(pending) } } }
        scope.launch {
            coordinator.promptEvents.collect {
                karooSystem.dispatch(
                    SystemNotification(
                        id = NOTIFICATION_ID,
                        header = "Ride rating",
                        message = "How hard was this ride?",
                        subText = "Rate RPE before ending the ride",
                        action = "Rate",
                        actionIntent = RateRideActivity.ACTION_RATE_RIDE,
                    ),
                )
            }
        }
    }

    private suspend fun syncToIntervals(pending: PendingSync) {
        val apiKey = BuildConfig.INTERVALS_API_KEY
        if (apiKey.isBlank()) {
            repository.completeSync("intervals.icu sync is off (no API key in this build)")
            return
        }
        val syncer = IntervalsSyncer(apiKey) { karooSystem.http(it) }
        val outcome = runSync(pending, syncer, now = System::currentTimeMillis)
        val rated = listOfNotNull(pending.rpe?.let { "RPE $it" }, pending.feel?.label).joinToString(" · ")
        val (status, style) = when (outcome) {
            is SyncOutcome.Done -> "$rated sent to intervals.icu" to SystemNotification.Style.EVENT
            is SyncOutcome.Fatal -> "intervals.icu sync failed: ${outcome.reason}" to SystemNotification.Style.ERROR
            else -> return // runSync only returns Done or Fatal
        }
        repository.completeSync(status)
        karooSystem.dispatch(
            SystemNotification(id = SYNC_NOTIFICATION_ID, header = "Ride rating", message = status, style = style),
        )
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        val job = scope.launch {
            coordinator().sessionWrites.collect { values ->
                emitter.onNext(WriteToSessionMesg(values))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun coordinator() = RideRatingCoordinator(
        rideState = karooSystem.consumerFlow<RideState>(),
        rating = repository.ratingFlow,
    )

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo_rpe"
        private const val NOTIFICATION_ID = "ride_rating"
        private const val SYNC_NOTIFICATION_ID = "ride_rating_sync"
    }
}
