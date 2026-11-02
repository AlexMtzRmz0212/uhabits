package org.isoron.uhabits.core.models

/**
 * Optional pinning of a habit in the habit list. Pinned habits ignore their category: a habit
 * pinned to the top is listed before everything else, and a habit pinned to the bottom is listed
 * after everything else.
 */
enum class HabitPin(val value: Int) {
    NONE(0),
    TOP(1),
    BOTTOM(2);

    companion object {
        fun fromInt(value: Int): HabitPin = entries.firstOrNull { it.value == value } ?: NONE
    }
}
