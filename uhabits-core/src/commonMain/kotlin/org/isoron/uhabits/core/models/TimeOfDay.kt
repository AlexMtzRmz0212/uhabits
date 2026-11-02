package org.isoron.uhabits.core.models

/**
 * Encoding of "time of day" entries. The entry value is [BASE] plus the number of minutes since
 * midnight, so that it never collides with the special values (NO, YES_AUTO, YES_MANUAL, SKIP,
 * UNKNOWN), which are all smaller than [BASE].
 */
object TimeOfDay {
    const val BASE = 1000
    const val MINUTES_PER_DAY = 24 * 60

    fun encode(minutes: Int): Int = BASE + minutes.coerceIn(0, MINUTES_PER_DAY - 1)

    fun isTime(value: Int): Boolean = value >= BASE

    /** Minutes since midnight, or null if the entry value does not hold a time. */
    fun minutesOrNull(value: Int): Int? = if (isTime(value)) value - BASE else null
}
