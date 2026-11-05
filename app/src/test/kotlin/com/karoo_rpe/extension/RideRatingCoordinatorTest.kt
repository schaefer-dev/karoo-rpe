package com.karoo_rpe.extension

import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideRatingCoordinatorTest {

    private val manualPause = RideState.Paused(auto = false)
    private val autoPause = RideState.Paused(auto = true)

    private class Harness(
        val state: MutableStateFlow<RideState>,
        val rating: MutableStateFlow<RideRating>,
        val writes: MutableList<List<FieldValue>>,
        val prompts: MutableList<Unit>,
        val resets: MutableList<Unit>,
        val actives: MutableList<Unit>,
    )

    private fun TestScope.harness(initial: RideState): Harness {
        val state = MutableStateFlow(initial)
        val rating = MutableStateFlow(RideRating())
        val c = RideRatingCoordinator(state, rating)
        val h = Harness(state, rating, mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf())
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { c.sessionWrites.collect { h.writes += it } }
        backgroundScope.launch(d) { c.promptEvents.collect { h.prompts += it } }
        backgroundScope.launch(d) { c.resetEvents.collect { h.resets += it } }
        backgroundScope.launch(d) { c.rideActiveEvents.collect { h.actives += it } }
        return h
    }

    @Test
    fun `rating change while paused is written without any elapsed-time tick`() = runTest {
        val h = harness(manualPause)
        h.rating.value = RideRating(rpe = 6)
        h.rating.value = RideRating(rpe = 6, feel = Feel.NORMAL)
        assertEquals(
            listOf(
                listOf(FieldValue(RPE_DEV_FIELD, 60.0)),
                listOf(FieldValue(RPE_DEV_FIELD, 60.0), FieldValue(FEEL_DEV_FIELD, 50.0)),
            ),
            h.writes,
        )
    }

    @Test
    fun `last rating wins and unchanged rating is not rewritten on state changes`() = runTest {
        val h = harness(RideState.Recording)
        h.rating.value = RideRating(rpe = 5)
        h.state.value = manualPause
        h.state.value = RideState.Recording
        h.rating.value = RideRating(rpe = 8)
        assertEquals(listOf(listOf(FieldValue(RPE_DEV_FIELD, 50.0)), listOf(FieldValue(RPE_DEV_FIELD, 80.0))), h.writes)
    }

    @Test
    fun `nothing is written while idle`() = runTest {
        val h = harness(RideState.Idle)
        h.rating.value = RideRating(rpe = 7)
        assertTrue(h.writes.isEmpty())
    }

    @Test
    fun `manual pause with unset rpe prompts once`() = runTest {
        val h = harness(RideState.Recording)
        h.state.value = manualPause
        // Rating feel only (rpe still unset) must not re-prompt while staying paused.
        h.rating.value = RideRating(feel = Feel.GOOD)
        assertEquals(1, h.prompts.size)
    }

    @Test
    fun `each new manual pause prompts again while rpe is unset`() = runTest {
        val h = harness(RideState.Recording)
        h.state.value = manualPause
        h.state.value = RideState.Recording
        h.state.value = manualPause
        assertEquals(2, h.prompts.size)
    }

    @Test
    fun `no prompt once rpe is set`() = runTest {
        val h = harness(RideState.Recording)
        h.rating.value = RideRating(rpe = 7)
        h.state.value = manualPause
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `auto pause does not prompt`() = runTest {
        val h = harness(RideState.Recording)
        h.state.value = autoPause
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `switching from auto pause to manual pause prompts`() = runTest {
        val h = harness(autoPause)
        h.state.value = manualPause
        assertEquals(1, h.prompts.size)
    }

    @Test
    fun `idle resets the rating, including at startup`() = runTest {
        val h = harness(RideState.Idle)
        assertEquals(1, h.resets.size)
        h.state.value = RideState.Recording
        h.state.value = manualPause
        assertEquals(1, h.resets.size)
        h.state.value = RideState.Idle
        assertEquals(2, h.resets.size)
    }

    @Test
    fun `service started mid-ride does not reset`() = runTest {
        val h = harness(RideState.Recording)
        assertTrue(h.resets.isEmpty())
    }

    @Test
    fun `ride active fires once per ride, pauses included`() = runTest {
        val h = harness(RideState.Idle)
        assertTrue(h.actives.isEmpty())
        h.state.value = RideState.Recording
        h.state.value = manualPause
        h.state.value = RideState.Recording
        assertEquals(1, h.actives.size)
        h.state.value = RideState.Idle
        h.state.value = RideState.Recording
        assertEquals(2, h.actives.size)
    }
}
