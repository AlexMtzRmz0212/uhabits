package org.isoron.uhabits.core.sync

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.query
import org.isoron.platform.io.run
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryFolder : SyncFolder {
    val files = mutableMapOf<String, String>()
    override fun listFiles() = files.keys.toList()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) {
        files[name] = text
    }
}

class SyncEngineTest {
    private val day = 86_400_000L

    private class Device(val db: Database, folder: SyncFolder, startMillis: Long) {
        var now = startMillis
        val sync = SyncRepository(db) { now }
        val habits = HabitRepository(db, sync)
        val entries = EntryRepository(db, sync)
        val engine = SyncEngine(db, sync, folder)

        fun addHabit(uuid: String, name: String = uuid, position: Int = 0): Long {
            now += 1
            return habits.insert(HabitData(name = name, uuid = uuid, position = position, freqNum = 1, freqDen = 1))
        }

        fun rename(uuid: String, name: String) {
            now += 1
            val id = sync.habitId(uuid)!!
            val current = habits.findAll().first { it.id == id }
            habits.update(current.copy(name = name))
        }

        fun check(uuid: String, d: Long, value: Int = 2, notes: String = "") {
            now += 1
            val id = sync.habitId(uuid)!!
            entries.deleteByHabitIdAndTimestamp(id, d * 86_400_000L)
            entries.insert(EntryData(habitId = id, timestamp = d * 86_400_000L, value = value, notes = notes))
        }

        fun uncheck(uuid: String, d: Long) {
            now += 1
            entries.deleteByHabitIdAndTimestamp(sync.habitId(uuid)!!, d * 86_400_000L)
        }

        fun deleteHabit(uuid: String) {
            now += 1
            val id = sync.habitId(uuid)!!
            entries.deleteByHabitId(id)
            habits.delete(id)
            // Like SQLiteHabitList.remove, close the gap left in the positions.
            for ((pos, h) in habits.findAll().withIndex()) {
                if (h.position != pos) habits.update(h.copy(position = pos))
            }
        }

        fun state(): List<String> {
            val out = mutableListOf<String>()
            db.query("select uuid, name, position, archived from Habits order by uuid") {
                out.add("H ${it.getText(0)} ${it.getText(1)} pos=${it.getInt(2)} arch=${it.getInt(3)}")
            }
            db.query(
                """select h.uuid, r.timestamp, r.value, r.notes from Repetitions r
                   join Habits h on h.id = r.habit order by h.uuid, r.timestamp"""
            ) { out.add("E ${it.getText(0)} ${it.getLong(1)} ${it.getInt(2)} ${it.getText(3)}") }
            return out
        }
    }

    private fun twoDevices(block: suspend (Device, Device, InMemoryFolder) -> Unit) = runTest {
        val folder = InMemoryFolder()
        val a = Device(TestDatabaseHelper.createEmptyDatabase(), folder, 1_000_000)
        val b = Device(TestDatabaseHelper.createEmptyDatabase(), folder, 1_000_000)
        block(a, b, folder)
        a.db.close()
        b.db.close()
    }

    /** Both devices sync twice, in the order a, b, a, b, so each sees the other's latest state. */
    private fun settle(a: Device, b: Device) {
        a.engine.syncOnce()
        b.engine.syncOnce()
        a.engine.syncOnce()
        b.engine.syncOnce()
    }

    @Test
    fun testFileRoundTripKeepsSpecialCharacters() {
        val habit = HabitData(
            name = "Run \"fast\" \\ today\nline2\ttab é 日本",
            uuid = "u1",
            targetValue = 12.5,
            reminderHour = 7,
            reminderMin = 30,
            category = "Health"
        )
        val snapshot = SyncSnapshot(
            SyncHeader(27, SyncFile.APP_ID, "dev"),
            listOf(
                HabitRecord("h:u1", "0000000000001-00000-dev", habit),
                EntryRecord("e:u1:5", "0000000000002-00000-dev", "u1", 5 * day, 3, "note \"x\"\n"),
                TombstoneRecord("e:u1:6", "0000000000003-00000-dev")
            )
        )
        val decoded = SyncFile.decode(SyncFile.encode(snapshot))
        assertEquals(snapshot.header, decoded.header)
        assertEquals(snapshot.records.sortedBy { it.key }, decoded.records)
    }

    @Test
    fun testDecodeRejectsGarbage() {
        assertFailsWith<UnsupportedSyncFileException> { SyncFile.decode("") }
        assertFailsWith<UnsupportedSyncFileException> { SyncFile.decode("not json") }
        assertFailsWith<UnsupportedSyncFileException> { SyncFile.decode("{\"t\":\"habit\"}") }
    }

    @Test
    fun testNewHabitAndEntriesReachTheOtherDevice() = twoDevices { a, b, _ ->
        a.addHabit("u1", "Read")
        a.check("u1", 10, 2, "good")
        a.check("u1", 11)
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertEquals(3, b.state().size)
    }

    @Test
    fun testOfflineEditsOnBothDevicesMerge() = twoDevices { a, b, _ ->
        a.addHabit("u1", "Read")
        settle(a, b)

        a.check("u1", 10)
        a.addHabit("ua", "Only on A", position = 1)
        b.check("u1", 11)
        b.addHabit("ub", "Only on B", position = 2)

        settle(a, b)
        assertEquals(a.state(), b.state())
        assertTrue(a.state().any { it.startsWith("E u1 ${10 * day}") })
        assertTrue(a.state().any { it.startsWith("E u1 ${11 * day}") })
        assertTrue(a.state().any { it.startsWith("H ua") })
        assertTrue(a.state().any { it.startsWith("H ub") })
    }

    @Test
    fun testSameKeyConflictLaterEditWins() = twoDevices { a, b, _ ->
        a.addHabit("u1", "Read")
        a.check("u1", 10, 1)
        settle(a, b)

        a.now += 1_000
        a.check("u1", 10, 2)
        b.now += 5_000
        b.check("u1", 10, 3)

        settle(a, b)
        assertEquals(a.state(), b.state())
        assertTrue(a.state().any { it == "E u1 ${10 * day} 3 " })
    }

    @Test
    fun testHabitDeletionReachesOtherDevice() = twoDevices { a, b, _ ->
        a.addHabit("u1")
        a.addHabit("u2", position = 1)
        a.check("u1", 10)
        settle(a, b)
        assertEquals(a.state(), b.state())

        a.deleteHabit("u1")
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertFalse(b.state().any { it.contains("u1") })
        assertTrue(b.state().any { it.startsWith("H u2") })
    }

    @Test
    fun testEntryDeletionReachesOtherDevice() = twoDevices { a, b, _ ->
        a.addHabit("u1")
        a.check("u1", 10)
        a.check("u1", 11)
        settle(a, b)

        a.uncheck("u1", 10)
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertFalse(b.state().any { it.startsWith("E u1 ${10 * day}") })
        assertTrue(b.state().any { it.startsWith("E u1 ${11 * day}") })
    }

    @Test
    fun testDeletedEntryDoesNotComeBackFromOldFile() = twoDevices { a, b, _ ->
        a.addHabit("u1")
        a.check("u1", 10)
        settle(a, b)

        b.now += 10_000
        b.uncheck("u1", 10)
        // A still has the entry in its file; B must not take it back.
        b.engine.syncOnce()
        b.engine.syncOnce()
        assertFalse(b.state().any { it.startsWith("E u1") })
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertFalse(a.state().any { it.startsWith("E u1") })
    }

    @Test
    fun testEditAfterRemoteDeletionOfEntryRestoresIt() = twoDevices { a, b, _ ->
        a.addHabit("u1")
        a.check("u1", 10, 1)
        settle(a, b)

        a.uncheck("u1", 10)
        b.now += 10_000
        b.check("u1", 10, 3)
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertTrue(a.state().any { it == "E u1 ${10 * day} 3 " })
    }

    @Test
    fun testSecondRoundDoesNothing() = twoDevices { a, b, _ ->
        a.addHabit("u1")
        a.check("u1", 10)
        settle(a, b)

        val ra = a.engine.syncOnce()
        val rb = b.engine.syncOnce()
        assertEquals(0, ra.peersMerged)
        assertEquals(0, rb.peersMerged)
        assertFalse(ra.wroteOwnFile)
        assertFalse(rb.wroteOwnFile)
    }

    @Test
    fun testMergingSameFileTwiceChangesNothing() = twoDevices { a, b, folder ->
        a.addHabit("u1")
        a.check("u1", 10)
        a.engine.syncOnce()
        val snapshot = SyncFile.decode(folder.files.values.first())
        val merger = SyncMerger(b.db, b.sync)

        assertTrue(merger.merge(snapshot).changed)
        val after = b.state()
        val second = merger.merge(snapshot)
        assertFalse(second.changed)
        assertEquals(after, b.state())
    }

    @Test
    fun testDeviceWithSlowClockStillWinsAfterSeeingNewerState() = twoDevices { a, b, _ ->
        a.now = 50_000_000
        b.now = 1_000_000
        a.addHabit("u1", "Read")
        a.check("u1", 10, 1)
        settle(a, b)

        // B's wall clock is far behind A's, but B has seen A's stamps, so its edit sorts later.
        b.check("u1", 10, 3)
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertTrue(a.state().any { it == "E u1 ${10 * day} 3 " })
    }

    @Test
    fun testRenameOnOneDeviceReachesOther() = twoDevices { a, b, _ ->
        a.addHabit("u1", "Read")
        settle(a, b)
        b.now += 5_000
        b.rename("u1", "Read more")
        settle(a, b)
        assertTrue(a.state().any { it.startsWith("H u1 Read more") })
        assertEquals(a.state(), b.state())
    }

    @Test
    fun testHabitsWithSamePositionGetDistinctPositions() = twoDevices { a, b, _ ->
        a.addHabit("ua", position = 0)
        b.addHabit("ub", position = 0)
        settle(a, b)
        assertEquals(a.state(), b.state())
        val positions = a.state().filter { it.startsWith("H") }.map { it.substringAfter("pos=").substringBefore(" ") }
        assertEquals(listOf("0", "1"), positions.sorted())
    }

    @Test
    fun testRowsThatExistedBeforeSyncAreStampedAndShared() = twoDevices { a, b, _ ->
        a.db.run("insert into Habits(name, description, question, unit, freq_num, freq_den, color, position, archived, type, uuid) values ('Old', '', '', '', 1, 1, 0, 0, 0, 0, 'old1')")
        a.db.run("insert into Repetitions(habit, timestamp, value, notes) values (1, ${10 * day}, 2, '')")
        settle(a, b)
        assertEquals(a.state(), b.state())
        assertEquals(2, b.state().size)
    }

    @Test
    fun testNewerSchemaFileIsRefusedAndOthersStillMerge() = twoDevices { a, b, folder ->
        a.addHabit("u1")
        a.engine.syncOnce()
        folder.files["zzz.sync.gz"] = SyncFile.encode(SyncSnapshot(SyncHeader(999, SyncFile.APP_ID, "zzz"), emptyList()))

        val report = b.engine.syncOnce()
        assertEquals(1, report.errors.size)
        assertTrue(report.errors[0].startsWith("zzz.sync.gz"))
        assertTrue(b.state().any { it.startsWith("H u1") })
    }

    @Test
    fun testFileFromAnotherAppIsRefused() = twoDevices { a, _, _ ->
        val snapshot = SyncSnapshot(SyncHeader(27, "other-app", "zzz"), emptyList())
        assertFailsWith<UnsupportedSyncFileException> { SyncMerger(a.db, a.sync).merge(snapshot) }
    }

    @Test
    fun testOwnFileIsRewrittenIfDeletedFromFolder() = twoDevices { a, _, folder ->
        a.addHabit("u1")
        a.engine.syncOnce()
        folder.files.clear()
        assertTrue(a.engine.syncOnce().wroteOwnFile)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun testThreeDevicesConverge() = runTest {
        val folder = InMemoryFolder()
        val devices = List(3) { Device(TestDatabaseHelper.createEmptyDatabase(), folder, 1_000_000) }
        devices[0].addHabit("u1", "A habit")
        devices[1].addHabit("u2", "B habit", position = 1)
        devices[2].addHabit("u3", "C habit", position = 2)
        devices[0].check("u1", 1)
        devices[1].check("u2", 2)
        devices[2].check("u3", 3)
        repeat(3) { devices.forEach { it.engine.syncOnce() } }
        assertEquals(devices[0].state(), devices[1].state())
        assertEquals(devices[1].state(), devices[2].state())
        assertEquals(6, devices[0].state().size)
        devices.forEach { it.db.close() }
    }
}
