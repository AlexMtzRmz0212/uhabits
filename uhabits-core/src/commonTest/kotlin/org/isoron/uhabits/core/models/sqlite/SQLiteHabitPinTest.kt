package org.isoron.uhabits.core.models.sqlite

import kotlinx.coroutines.test.runTest
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.HabitPin
import org.isoron.uhabits.core.models.HabitType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pin of wake-up and bedtime habits is stored in the legacy "highlight" column, so that no
 * database migration is needed. These tests check that the value survives a trip through SQLite.
 */
class SQLiteHabitPinTest : BaseUnitTest() {

    @Test
    fun testTypeAndPinSurviveReload() = runTest {
        val db = buildMemoryDatabase()
        val list = SQLiteHabitList(SQLModelFactory(db))

        val wake = SQLModelFactory(db).buildHabit()
        wake.name = "Wake up"
        wake.type = HabitType.WAKE_UP
        wake.pin = HabitPin.TOP
        list.add(wake)

        val bed = SQLModelFactory(db).buildHabit()
        bed.name = "Bedtime"
        bed.type = HabitType.BEDTIME
        bed.pin = HabitPin.BOTTOM
        list.add(bed)

        val plain = SQLModelFactory(db).buildHabit()
        plain.name = "Plain"
        list.add(plain)

        val reloaded = SQLiteHabitList(SQLModelFactory(db))
        val wakeBack = reloaded.getById(wake.id!!)!!
        val bedBack = reloaded.getById(bed.id!!)!!
        val plainBack = reloaded.getById(plain.id!!)!!

        assertEquals(HabitType.WAKE_UP, wakeBack.type)
        assertEquals(HabitPin.TOP, wakeBack.effectivePin)
        assertEquals(HabitType.BEDTIME, bedBack.type)
        assertEquals(HabitPin.BOTTOM, bedBack.effectivePin)
        assertEquals(HabitType.YES_NO, plainBack.type)
        assertEquals(HabitPin.NONE, plainBack.effectivePin)
        assertEquals(listOf("Wake up", "Plain", "Bedtime"), reloaded.map { it.name })
    }

    @Test
    fun testLegacyHighlightValueIsIgnoredForOtherHabits() = runTest {
        val db = buildMemoryDatabase()
        val list = SQLiteHabitList(SQLModelFactory(db))
        val habit = SQLModelFactory(db).buildHabit()
        habit.name = "Old habit"
        habit.pin = HabitPin.TOP // as if an old backup had highlight = 1 on a normal habit
        list.add(habit)

        val back = SQLiteHabitList(SQLModelFactory(db)).getById(habit.id!!)!!
        assertEquals(HabitType.YES_NO, back.type)
        assertEquals(HabitPin.NONE, back.effectivePin)
    }
}
