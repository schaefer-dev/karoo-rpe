package com.karoo_rpe.extension

import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold

/**
 * Pure-flow coordination for the ride rating. No Android or SDK service types, so it is fully
 * JVM-testable; [RpeExtension] only wires these flows to the Karoo.
 *
 * Writes are driven by rating *changes*, never by ELAPSED_TIME (which stops while paused, the
 * moment riders usually rate).
 */
class RideRatingCoordinator(
    private val rideState: Flow<RideState>,
    private val rating: Flow<RideRating>,
) {

    /**
     * Session field values to write, emitted whenever the rating changes during an open ride
     * (Recording or Paused). Karoo keeps the last values written and puts them in the final
     * SessionMesg when the ride ends.
     */
    val sessionWrites: Flow<List<FieldValue>> =
        combine(rideState, rating) { state, r ->
            if (state is RideState.Idle) emptyList() else r.toSessionValues()
        }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()

    /**
     * Fires when the rider manually pauses while RPE is still unset. Auto-pauses (traffic
     * lights) are ignored so the prompt doesn't nag mid-ride.
     */
    val promptEvents: Flow<Unit> =
        combine(rideState.transitions(), rating) { transition, r -> transition to r }
            // Re-evaluate only when the ride state changes, not on every rating edit.
            .distinctUntilChanged { old, new -> old.first === new.first }
            .mapNotNull { (transition, r) ->
                val (prev, curr) = transition.prev to transition.curr
                val manualPause = curr is RideState.Paused && !curr.auto
                val wasManualPause = prev is RideState.Paused && !prev.auto
                if (manualPause && !wasManualPause && r.rpe == null) Unit else null
            }

    /**
     * Fires when a ride is open (Recording or Paused) after not being open, including at startup
     * mid-ride. Used to record the ride's start time (kept if already set).
     */
    val rideActiveEvents: Flow<Unit> =
        rideState
            .map { it !is RideState.Idle }
            .distinctUntilChanged()
            .filter { it }
            .map { }

    /**
     * Fires whenever there is no open ride (Idle). The previous ride's rating is no longer
     * writable then, so it is handed to the intervals.icu sync and cleared so it can't leak into
     * the next ride.
     */
    val resetEvents: Flow<Unit> =
        rideState
            .map { it is RideState.Idle }
            .distinctUntilChanged()
            .filter { it }
            .map { }

    /** Wrapper so each state change is a distinct object (identity) for de-duplication above. */
    private class Transition(val prev: RideState?, val curr: RideState)

    private fun Flow<RideState>.transitions(): Flow<Transition> =
        distinctUntilChanged()
            .runningFold<RideState, Transition?>(null) { acc, state -> Transition(acc?.curr, state) }
            .mapNotNull { it }
}
