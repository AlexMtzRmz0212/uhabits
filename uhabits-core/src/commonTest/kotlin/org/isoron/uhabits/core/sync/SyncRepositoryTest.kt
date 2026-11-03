package org.isoron.uhabits.core.sync

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRepositoryTest {
    private val day = 86_400_000L

    private class Fixture(val db: Database) {
        var now = 1_000_000L
        val sync = SyncRepository(db) { now }
        val habits = HabitRepository(db, sync)
        val entries = EntryRepository(db, sync)

        fun newHabit(uuid: String = "u1", position: Int = 0): Long =
            habits.insert(HabitData(name = uuid, uuid = uuid, position = position, freqNum = 1, freqDen = 1))
    }

    private fun fixtureTest(block: suspend Fixture.() -> Unit) = runTest {
        val f = Fixture(TestDatabaseHelper.createEmptyDatabase())
        f.block()
        f.db.close()
    }

    @Test
    fun testDeviceIdIsCreatedOnceAndPersisted() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val first = SyncRepository(db).deviceId
        val second = SyncRepository(db).deviceId
        assertEquals(12, first.length)
        assertEquals(first, second)
        db.close()
    }

    @Test
    fun testInsertingHabitStampsIt() = fixtureTest {
        newHabit("u1")
        val rec = sync.get("h:u1")
        assertNotNull(rec)
        assertFalse(rec.deleted)
    }

    @Test
    fun testUpdatingHabitGivesItANewerStamp() = fixtureTest {
        val id = newHabit("u1")
        val before = sync.get("h:u1")!!.hlc
        now += 10
        habits.update(HabitData(id = id, name = "renamed", uuid = "u1", freqNum = 1, freqDen = 1))
        assertTrue(sync.get("h:u1")!!.hlc > before)
    }

    @Test
    fun testDeletingHabitLeavesTombstone() = fixtureTest {
        val id = newHabit("u1")
        now += 10
        habits.delete(id)
        assertTrue(sync.get("h:u1")!!.deleted)
    }

    @Test
    fun testInsertingEntryStampsItByHabitAndDay() = fixtureTest {
        val id = newHabit("u1")
        entries.insert(EntryData(habitId = id, timestamp = 3 * day, value = 2))
        val rec = sync.get("e:u1:3")
        assertNotNull(rec)
        assertFalse(rec.deleted)
    }

    @Test
    fun testDeletingEntryLeavesTombstone() = fixtureTest {
        val id = newHabit("u1")
        entries.insert(EntryData(habitId = id, timestamp = 3 * day, value = 2))
        now += 10
        entries.deleteByHabitIdAndTimestamp(id, 3 * day)
        assertTrue(sync.get("e:u1:3")!!.deleted)
    }

    @Test
    fun testReplacingEntryEndsUpLive() = fixtureTest {
        val id = newHabit("u1")
        entries.insert(EntryData(habitId = id, timestamp = 3 * day, value = 2))
        entries.deleteByHabitIdAndTimestamp(id, 3 * day)
        entries.insert(EntryData(habitId = id, timestamp = 3 * day, value = 1))
        assertFalse(sync.get("e:u1:3")!!.deleted)
    }

    @Test
    fun testDeletingAllEntriesTombstonesEachOne() = fixtureTest {
        val id = newHabit("u1")
        entries.insert(EntryData(habitId = id, timestamp = 1 * day, value = 2))
        entries.insert(EntryData(habitId = id, timestamp = 2 * day, value = 2))
        entries.deleteByHabitId(id)
        assertTrue(sync.get("e:u1:1")!!.deleted)
        assertTrue(sync.get("e:u1:2")!!.deleted)
    }

    @Test
    fun testSuppressedWritesAreNotStamped() = fixtureTest {
        sync.suppressed { newHabit("u1") }
        assertNull(sync.get("h:u1"))
        assertFalse(sync.isSuppressed)
    }

    @Test
    fun testTouchPositionsStampsOnlyHabitsInRange() = fixtureTest {
        newHabit("a", 0)
        newHabit("b", 1)
        newHabit("c", 2)
        val before = sync.get("h:c")!!.hlc
        now += 10
        habits.touchPositions(0, 1)
        assertTrue(sync.get("h:a")!!.hlc > before)
        assertTrue(sync.get("h:b")!!.hlc > before)
        assertEquals(before, sync.get("h:c")!!.hlc)
    }

    @Test
    fun testApplyRemoteKeepsLocalClockAhead() = fixtureTest {
        val remote = SyncClock("bbb", nowMillis = { 9_000_000 }).next()
        sync.applyRemote("h:u9", remote, deleted = false)
        newHabit("u1")
        assertTrue(sync.get("h:u1")!!.hlc > remote)
        assertEquals(remote, sync.get("h:u9")!!.hlc)
    }

    @Test
    fun testClockSurvivesRestart() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        var now = 9_000L
        val first = SyncRepository(db) { now }
        first.stamp("h:x", false)
        val stamped = first.get("h:x")!!.hlc
        now = 1_000L
        val second = SyncRepository(db) { now }
        second.stamp("h:y", false)
        assertTrue(second.get("h:y")!!.hlc > stamped)
        db.close()
    }
}
