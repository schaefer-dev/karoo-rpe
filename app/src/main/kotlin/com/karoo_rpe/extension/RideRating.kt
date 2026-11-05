package com.karoo_rpe.extension

import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue

/**
 * Native FIT SessionMesg field numbers (Garmin FIT profile), written by Garmin head units after
 * the post-ride "How did it feel?" prompt and read on import by intervals.icu / TrainingPeaks.
 *
 * - `workout_feel` (192): uint8, 0–100, higher = felt better.
 * - `workout_rpe`  (193): uint8, Borg CR10 RPE multiplied by 10 (RPE 7 → 70).
 *
 * Karoo 3 firmware (ROM 1.665) ships a FIT profile that predates these fields: writing them as
 * native fields produces invalid `unknown_255` entries. So they are written as developer fields
 * with the same name, scale and a `native_field_num` hint, which decoders may map back.
 */
const val FIT_SESSION_WORKOUT_FEEL = 192
const val FIT_SESSION_WORKOUT_RPE = 193

/** FIT base type id for uint8. */
private const val FIT_BASE_TYPE_UINT8: Short = 2

val RPE_DEV_FIELD = DeveloperField(
    fieldDefinitionNumber = 0,
    fitBaseTypeId = FIT_BASE_TYPE_UINT8,
    fieldName = "workout_rpe",
    units = "",
    nativeFieldNum = FIT_SESSION_WORKOUT_RPE.toShort(),
)

val FEEL_DEV_FIELD = DeveloperField(
    fieldDefinitionNumber = 1,
    fitBaseTypeId = FIT_BASE_TYPE_UINT8,
    fieldName = "workout_feel",
    units = "",
    nativeFieldNum = FIT_SESSION_WORKOUT_FEEL.toShort(),
)

const val RPE_MIN = 1
const val RPE_MAX = 10

/**
 * Feel on the 5-step scale Garmin and intervals.icu use. [fitValue] is the raw `workout_feel`
 * value Garmin writes for each step.
 */
enum class Feel(val fitValue: Int, val label: String) {
    WEAK(0, "Weak"),
    POOR(25, "Poor"),
    NORMAL(50, "Normal"),
    GOOD(75, "Good"),
    STRONG(100, "Strong"),
}

/** The rider's rating of the current ride. Either part may be unset. */
data class RideRating(val rpe: Int? = null, val feel: Feel? = null) {
    val isEmpty: Boolean get() = rpe == null && feel == null
}

/** Session developer-field values for the set parts of the rating; unset parts are omitted. */
fun RideRating.toSessionValues(): List<FieldValue> = buildList {
    rpe?.takeIf { it in RPE_MIN..RPE_MAX }?.let { add(FieldValue(RPE_DEV_FIELD, it * 10.0)) }
    feel?.let { add(FieldValue(FEEL_DEV_FIELD, it.fitValue.toDouble())) }
}

/** Common Borg CR10 descriptors, shown next to the numbers in the picker. */
fun rpeLabel(rpe: Int): String = when (rpe) {
    1 -> "Very easy"
    2 -> "Easy"
    3 -> "Moderate"
    4 -> "Somewhat hard"
    5, 6 -> "Hard"
    7, 8 -> "Very hard"
    9 -> "Extremely hard"
    10 -> "Max effort"
    else -> ""
}

/** Short text for the in-ride field, e.g. "7 · Good", or null when nothing is rated yet. */
fun RideRating.summary(): String? = when {
    rpe != null && feel != null -> "$rpe · ${feel.label}"
    rpe != null -> "$rpe"
    feel != null -> feel.label
    else -> null
}
