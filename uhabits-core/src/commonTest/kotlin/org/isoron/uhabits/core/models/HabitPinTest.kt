package org.isoron.uhabits.core.models

import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HabitPinTest : BaseUnitTest() {

    @BeforeTest
    override fun setUp() {
        super.setUp()
    }

    private fun addHabit(
        name: String,
        category: String = "",
        pin: HabitPin = HabitPin.NONE,
        type: HabitType = HabitType.YES_NO
    ): Habit {
        val habit = modelFactory.buildHabit()
        habit.name = name
        habit.category = category
        habit.pin = pin
        habit.type = type
        habitList.add(habit)
        return habit
    }

    @Test
    fun testPinnedHabitsAreListedFirstAndLast() {
        addHabit("Bottom", pin = HabitPin.BOTTOM, type = HabitType.BEDTIME)
        addHabit("Work habit", category = "Work")
        addHabit("Plain habit")
        addHabit("Top", category = "Work", pin = HabitPin.TOP, type = HabitType.WAKE_UP)

        assertEquals(
            listOf("Top", "Plain habit", "Work habit", "Bottom"),
            habitList.map { it.name }
        )
    }

    @Test
    fun testUnpinnedHabitsKeepTheirNormalOrder() {
        addHabit("Wake up", pin = HabitPin.NONE, type = HabitType.WAKE_UP)
        addHabit("Other")
        addHabit("Bedtime", pin = HabitPin.NONE, type = HabitType.BEDTIME)

        // Without pins, the default (alphabetical) order applies.
        assertEquals(listOf("Bedtime", "Other", "Wake up"), habitList.map { it.name })
    }

    @Test
    fun testPinnedHabitsIgnoreTheirCategory() {
        val pinned = addHabit("Top", category = "Work", pin = HabitPin.TOP, type = HabitType.WAKE_UP)
        val normal = addHabit("Normal", category = "Work")
        assertEquals("", pinned.effectiveCategory)
        assertEquals("Work", normal.effectiveCategory)
    }

    @Test
    fun testPinValuesRoundTrip() {
        for (pin in HabitPin.entries) {
            assertEquals(pin, HabitPin.fromInt(pin.value))
        }
        assertEquals(HabitPin.NONE, HabitPin.fromInt(99))
    }

    @Test
    fun testNewTypesAreTimeOfDay() {
        for (type in listOf(HabitType.TIME_OF_DAY, HabitType.WAKE_UP, HabitType.BEDTIME)) {
            assertEquals(type, HabitType.fromInt(type.value))
            assertEquals(true, type.isTimeOfDay)
        }
        assertEquals(false, HabitType.YES_NO.isTimeOfDay)
        assertEquals(false, HabitType.NUMERICAL.isTimeOfDay)
    }
}
