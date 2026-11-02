package org.isoron.uhabits.core.models

/**
 * The codes stored in the database. The values 10 and above are reserved for this fork, so that
 * they cannot clash with types that the upstream project may add later (upstream uses the small
 * numbers).
 */
enum class HabitType(val value: Int) {
    YES_NO(0),
    NUMERICAL(1),

    /** A habit whose entry is a time of day. */
    TIME_OF_DAY(10),

    /** A time-of-day habit for the time the user wakes up. At most one per database. */
    WAKE_UP(11),

    /** A time-of-day habit for the time the user goes to bed. At most one per database. */
    BEDTIME(12);

    /** Whether entries of this type hold a time of day. */
    val isTimeOfDay: Boolean
        get() = this == TIME_OF_DAY || this == WAKE_UP || this == BEDTIME

    companion object {
        fun fromInt(value: Int): HabitType {
            return when (value) {
                YES_NO.value -> YES_NO
                NUMERICAL.value -> NUMERICAL
                TIME_OF_DAY.value -> TIME_OF_DAY
                WAKE_UP.value -> WAKE_UP
                BEDTIME.value -> BEDTIME
                else -> throw IllegalStateException()
            }
        }
    }
}
