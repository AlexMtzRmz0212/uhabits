package org.isoron.uhabits.core.models

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeOfDayHabitTest : BaseUnitTest() {

    @BeforeTest
    override fun setUp() {
        super.setUp()
    }

    @Test
    fun testEncoding() {
        assertEquals(1000, TimeOfDay.encode(0))
        assertEquals(1000 + 7 * 60 + 30, TimeOfDay.encode(7 * 60 + 30))
        assertEquals(7 * 60 + 30, TimeOfDay.minutesOrNull(TimeOfDay.encode(7 * 60 + 30)))
        assertEquals(0, TimeOfDay.minutesOrNull(TimeOfDay.encode(0)))
        for (special in listOf(Entry.UNKNOWN, Entry.NO, Entry.YES_AUTO, Entry.YES_MANUAL, Entry.SKIP)) {
            assertNull(TimeOfDay.minutesOrNull(special))
        }
    }

    @Test
    fun testHabitTypeRoundTrip() {
        assertEquals(HabitType.TIME_OF_DAY, HabitType.fromInt(HabitType.TIME_OF_DAY.value))
    }

    @Test
    fun testLoggedTimeCountsAsDone() {
        val habit = modelFactory.buildHabit()
        habit.type = HabitType.TIME_OF_DAY
        assertTrue(habit.isTimeOfDay)
        assertFalse(habit.isNumerical)

        val today = getToday()
        habit.originalEntries.add(Entry(today, TimeOfDay.encode(0)))
        habit.recompute()

        assertTrue(habit.isCompletedToday())
        assertEquals(Entry.YES_MANUAL, habit.computedEntries.get(today).value)
        // The original entry keeps the logged time, including midnight.
        assertEquals(0, TimeOfDay.minutesOrNull(habit.originalEntries.get(today).value))
        assertTrue(habit.scores[today].value > 0.0)
    }

    private fun isDoneWith(type: NumericalHabitType, target: Double, minutes: Int): Boolean {
        val habit = modelFactory.buildHabit()
        habit.type = HabitType.TIME_OF_DAY
        habit.targetType = type
        habit.targetValue = target
        habit.originalEntries.add(Entry(getToday(), TimeOfDay.encode(minutes)))
        habit.recompute()
        return habit.isCompletedToday()
    }

    @Test
    fun testBeforeTarget() {
        val bedtime = 23 * 60.0
        assertTrue(isDoneWith(NumericalHabitType.AT_MOST, bedtime, 22 * 60 + 30))
        assertTrue(isDoneWith(NumericalHabitType.AT_MOST, bedtime, 23 * 60))
        assertFalse(isDoneWith(NumericalHabitType.AT_MOST, bedtime, 23 * 60 + 30))
    }

    @Test
    fun testAfterTarget() {
        val sixAm = 6 * 60.0
        assertFalse(isDoneWith(NumericalHabitType.AT_LEAST, sixAm, 5 * 60 + 59))
        assertTrue(isDoneWith(NumericalHabitType.AT_LEAST, sixAm, 6 * 60))
    }

    @Test
    fun testNoTargetAcceptsAnyTime() {
        assertTrue(isDoneWith(NumericalHabitType.AT_MOST, 0.0, 23 * 60 + 59))
        assertTrue(isDoneWith(NumericalHabitType.AT_LEAST, 0.0, 0))
    }

    @Test
    fun testMissedTargetKeepsLoggedTime() {
        val habit = modelFactory.buildHabit()
        habit.type = HabitType.TIME_OF_DAY
        habit.targetType = NumericalHabitType.AT_MOST
        habit.targetValue = 23 * 60.0
        val today = getToday()
        habit.originalEntries.add(Entry(today, TimeOfDay.encode(23 * 60 + 45)))
        habit.recompute()
        assertFalse(habit.isCompletedToday())
        assertEquals(23 * 60 + 45, TimeOfDay.minutesOrNull(habit.originalEntries.get(today).value))
    }

    @Test
    fun testClearedTimeIsNotDone() {
        val habit = modelFactory.buildHabit()
        habit.type = HabitType.TIME_OF_DAY
        val today = getToday()
        habit.originalEntries.add(Entry(today, TimeOfDay.encode(8 * 60)))
        habit.recompute()
        assertTrue(habit.isCompletedToday())

        habit.originalEntries.add(Entry(today, Entry.UNKNOWN))
        habit.recompute()
        assertFalse(habit.isCompletedToday())
    }
}
