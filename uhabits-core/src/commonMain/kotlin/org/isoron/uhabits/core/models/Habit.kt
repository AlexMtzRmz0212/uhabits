/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Habit(
    var category: String = "",
    var color: PaletteColor = PaletteColor(8),
    var description: String = "",
    var frequency: Frequency = Frequency.DAILY,
    var id: Long? = null,
    var isArchived: Boolean = false,
    var name: String = "",
    var pin: HabitPin = HabitPin.NONE,
    var position: Int = 0,
    var question: String = "",
    var reminder: Reminder? = null,
    var targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    var targetValue: Double = 0.0,
    var type: HabitType = HabitType.YES_NO,
    var unit: String = "",
    var uuid: String? = null,
    val computedEntries: EntryList,
    val originalEntries: EntryList,
    val scores: ScoreList,
    val streaks: StreakList
) {
    init {
        if (uuid == null) this.uuid = Uuid.random().toHexString()
    }

    var observable = ModelObservable()

    val isNumerical: Boolean
        get() = type == HabitType.NUMERICAL

    val isTimeOfDay: Boolean
        get() = type.isTimeOfDay

    /**
     * The list pin that is actually honored. Only wake-up and bedtime habits can be pinned; the
     * stored value is ignored for other habits (for example, old backups with legacy data in the
     * column that holds the pin).
     */
    val effectivePin: HabitPin
        get() = if (type == HabitType.WAKE_UP || type == HabitType.BEDTIME) pin else HabitPin.NONE

    /** The category used for grouping in the list. Pinned habits are never grouped. */
    val effectiveCategory: String
        get() = if (effectivePin == HabitPin.NONE) category else ""

    val uriString: String
        get() = "content://org.isoron.uhabits/habit/$id"

    fun hasReminder(): Boolean = reminder != null

    fun isCompletedOn(date: LocalDate): Boolean {
        return computedEntries.get(date).completed
    }

    fun isCompletedToday(): Boolean {
        return isCompletedOn(getToday())
    }

    fun isEnteredToday(): Boolean {
        val today = getToday()
        val value = computedEntries.get(today).value
        return value != Entry.UNKNOWN
    }

    fun recompute() {
        computedEntries.recomputeFrom(
            originalEntries = if (isTimeOfDay) timeEntriesAsYesNo() else originalEntries,
            frequency = frequency,
            isNumerical = isNumerical
        )

        computedEntries.recomputeCompletion(
            frequency = frequency,
            isNumerical = isNumerical,
            targetType = targetType,
            targetValue = targetValue
        )

        val today = getToday()
        val to = today.plus(30)
        val entries = computedEntries.getKnown()
        var from = entries.lastOrNull()?.date ?: today
        if (from.isNewerThan(to)) from = to

        scores.recompute(
            frequency = frequency,
            isNumerical = isNumerical,
            numericalHabitType = targetType,
            targetValue = targetValue,
            computedEntries = computedEntries,
            from = from,
            to = to
        )

        streaks.recompute(
            computedEntries,
            from,
            to,
            isNumerical,
            targetValue,
            targetType
        )
    }

    /**
     * For time-of-day habits, [targetValue] holds the target in minutes since midnight and
     * [targetType] says whether the logged time must be at least (after) or at most (before) the
     * target. A target of zero means there is no target.
     */
    fun meetsTimeTarget(minutes: Int): Boolean = when {
        targetValue <= 0.0 -> true
        targetType == NumericalHabitType.AT_MOST -> minutes <= targetValue
        else -> minutes >= targetValue
    }

    /**
     * Time-of-day habits are scored like yes/no habits: a day with a logged time that meets the
     * target counts as done, and a day with a logged time that misses it counts as not done. This
     * returns a copy of the original entries where each logged time becomes YES_MANUAL or NO.
     */
    private fun timeEntriesAsYesNo(): EntryList {
        val result = EntryList()
        for (entry in originalEntries.getKnown()) {
            val minutes = TimeOfDay.minutesOrNull(entry.value)
            val value = when {
                minutes == null -> entry.value
                meetsTimeTarget(minutes) -> Entry.YES_MANUAL
                else -> Entry.NO
            }
            result.add(entry.copy(value = value))
        }
        return result
    }

    fun copyFrom(other: Habit) {
        this.category = other.category
        this.color = other.color
        this.description = other.description
        this.frequency = other.frequency
        // this.id should not be copied
        this.isArchived = other.isArchived
        this.name = other.name
        this.pin = other.pin
        this.position = other.position
        this.question = other.question
        this.reminder = other.reminder
        this.targetType = other.targetType
        this.targetValue = other.targetValue
        this.type = other.type
        this.unit = other.unit
        this.uuid = other.uuid
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Habit) return false

        if (category != other.category) return false
        if (color != other.color) return false
        if (description != other.description) return false
        if (frequency != other.frequency) return false
        if (id != other.id) return false
        if (isArchived != other.isArchived) return false
        if (name != other.name) return false
        if (pin != other.pin) return false
        if (position != other.position) return false
        if (question != other.question) return false
        if (reminder != other.reminder) return false
        if (targetType != other.targetType) return false
        if (targetValue != other.targetValue) return false
        if (type != other.type) return false
        if (unit != other.unit) return false
        if (uuid != other.uuid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + isArchived.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + pin.hashCode()
        result = 31 * result + position
        result = 31 * result + question.hashCode()
        result = 31 * result + (reminder?.hashCode() ?: 0)
        result = 31 * result + targetType.value
        result = 31 * result + targetValue.hashCode()
        result = 31 * result + type.value
        result = 31 * result + unit.hashCode()
        result = 31 * result + (uuid?.hashCode() ?: 0)
        return result
    }
}
