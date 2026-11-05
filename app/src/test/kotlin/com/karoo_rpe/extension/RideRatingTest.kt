package com.karoo_rpe.extension

import io.hammerhead.karooext.models.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRatingTest {

    @Test
    fun `rpe is written as developer field hinting native 193, multiplied by ten`() {
        assertEquals(listOf(FieldValue(RPE_DEV_FIELD, 70.0)), RideRating(rpe = 7).toSessionValues())
    }

    @Test
    fun `feel is written as developer field hinting native 192, using Garmin steps`() {
        assertEquals(listOf(FieldValue(FEEL_DEV_FIELD, 75.0)), RideRating(feel = Feel.GOOD).toSessionValues())
        assertEquals(listOf(FieldValue(FEEL_DEV_FIELD, 0.0)), RideRating(feel = Feel.WEAK).toSessionValues())
        assertEquals(listOf(FieldValue(FEEL_DEV_FIELD, 100.0)), RideRating(feel = Feel.STRONG).toSessionValues())
    }

    @Test
    fun `both parts are written together`() {
        assertEquals(
            listOf(FieldValue(RPE_DEV_FIELD, 100.0), FieldValue(FEEL_DEV_FIELD, 25.0)),
            RideRating(rpe = 10, feel = Feel.POOR).toSessionValues(),
        )
    }

    @Test
    fun `unset or out-of-range parts are omitted`() {
        assertTrue(RideRating().toSessionValues().isEmpty())
        assertTrue(RideRating(rpe = 0).toSessionValues().isEmpty())
        assertTrue(RideRating(rpe = 11).toSessionValues().isEmpty())
    }

    @Test
    fun `summary text`() {
        assertEquals("7 · Good", RideRating(7, Feel.GOOD).summary())
        assertEquals("4", RideRating(rpe = 4).summary())
        assertEquals("Weak", RideRating(feel = Feel.WEAK).summary())
        assertNull(RideRating().summary())
    }
}
