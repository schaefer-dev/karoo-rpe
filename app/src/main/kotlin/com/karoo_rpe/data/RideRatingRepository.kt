package com.karoo_rpe.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.karoo_rpe.extension.Feel
import com.karoo_rpe.extension.RPE_MAX
import com.karoo_rpe.extension.RPE_MIN
import com.karoo_rpe.extension.RideRating
import com.karoo_rpe.sync.PendingSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ride_rating")

/**
 * The current ride's rating, persisted with DataStore so that both the picker activity and the
 * extension service see the same value, and so it survives a service restart mid-ride.
 * DataStore is a process singleton, and both components run in the app's process.
 *
 * Also holds the intervals.icu hand-off: the ride's start time, a finished ride's rating that is
 * waiting to be sent ([pendingFlow]), and a human-readable sync status.
 */
class RideRatingRepository(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    val ratingFlow: Flow<RideRating> = dataStore.data
        .map { prefs ->
            RideRating(
                rpe = prefs[KEY_RPE]?.takeIf { it in RPE_MIN..RPE_MAX },
                feel = prefs[KEY_FEEL]?.let { name -> Feel.entries.firstOrNull { it.name == name } },
            )
        }
        .distinctUntilChanged()

    suspend fun setRpe(rpe: Int) {
        dataStore.edit { it[KEY_RPE] = rpe.coerceIn(RPE_MIN, RPE_MAX) }
    }

    suspend fun setFeel(feel: Feel) {
        dataStore.edit { it[KEY_FEEL] = feel.name }
    }

    val pendingFlow: Flow<PendingSync?> = dataStore.data
        .map { prefs ->
            val start = prefs[KEY_PENDING_START] ?: return@map null
            PendingSync(
                rpe = prefs[KEY_PENDING_RPE],
                feel = prefs[KEY_PENDING_FEEL]?.let { name -> Feel.entries.firstOrNull { it.name == name } },
                rideStartMs = start,
                endedAtMs = prefs[KEY_PENDING_ENDED] ?: start,
            )
        }
        .distinctUntilChanged()

    val syncStatusFlow: Flow<String?> = dataStore.data.map { it[KEY_SYNC_STATUS] }.distinctUntilChanged()

    /** Records when the ride started; keeps an existing value (e.g. after a service restart). */
    suspend fun markRideStarted(nowMs: Long) {
        dataStore.edit { if (it[KEY_RIDE_START] == null) it[KEY_RIDE_START] = nowMs }
    }

    /**
     * Ride is over: atomically move a non-empty rating into the pending intervals.icu sync (only
     * when the ride's start time is known, so it can be matched) and clear the current rating.
     */
    suspend fun finishRide(nowMs: Long) {
        dataStore.edit { prefs ->
            val start = prefs[KEY_RIDE_START]
            val rpe = prefs[KEY_RPE]
            val feel = prefs[KEY_FEEL]
            if (start != null && (rpe != null || feel != null)) {
                prefs.remove(KEY_PENDING_RPE)
                prefs.remove(KEY_PENDING_FEEL)
                rpe?.let { prefs[KEY_PENDING_RPE] = it }
                feel?.let { prefs[KEY_PENDING_FEEL] = it }
                prefs[KEY_PENDING_START] = start
                prefs[KEY_PENDING_ENDED] = nowMs
                prefs[KEY_SYNC_STATUS] = "Waiting for the ride to reach intervals.icu…"
            }
            prefs.remove(KEY_RPE)
            prefs.remove(KEY_FEEL)
            prefs.remove(KEY_RIDE_START)
        }
    }

    /** Pending sync finished (success or give-up): drop it and record [status]. */
    suspend fun completeSync(status: String) {
        dataStore.edit {
            it.remove(KEY_PENDING_RPE)
            it.remove(KEY_PENDING_FEEL)
            it.remove(KEY_PENDING_START)
            it.remove(KEY_PENDING_ENDED)
            it[KEY_SYNC_STATUS] = status
        }
    }

    companion object {
        private val KEY_RPE = intPreferencesKey("rpe")
        private val KEY_FEEL = stringPreferencesKey("feel")
        private val KEY_RIDE_START = longPreferencesKey("ride_start_ms")
        private val KEY_PENDING_RPE = intPreferencesKey("pending_rpe")
        private val KEY_PENDING_FEEL = stringPreferencesKey("pending_feel")
        private val KEY_PENDING_START = longPreferencesKey("pending_start_ms")
        private val KEY_PENDING_ENDED = longPreferencesKey("pending_ended_ms")
        private val KEY_SYNC_STATUS = stringPreferencesKey("sync_status")
    }
}
