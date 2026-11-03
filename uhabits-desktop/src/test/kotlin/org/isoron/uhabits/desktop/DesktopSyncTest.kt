package org.isoron.uhabits.desktop

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSyncTest {
    private val tmp: Path = Files.createTempDirectory("myhabits-test")
    private val opened = mutableListOf<Database>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        tmp.toFile().deleteRecursively()
    }

    private fun open(name: String): Database = DesktopDatabase.open(tmp.resolve(name)).also { opened.add(it) }

    private fun habitNames(db: Database): List<String> {
        val names = mutableListOf<String>()
        db.query("select name from Habits order by name") { names.add(it.getText(0)) }
        return names
    }

    @Test
    fun testNewDatabaseIsCreatedAtCurrentVersion() {
        val db = open("a/uhabits.db")
        assertEquals(DATABASE_VERSION, db.getVersion())
        db.query("select count(*) from SyncMeta") { assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun testReopeningKeepsDataAndDoesNotMigrateAgain() {
        val path = tmp.resolve("uhabits.db")
        DesktopDatabase.open(path).use {
            HabitRepository(it).insert(HabitData(name = "Read", uuid = "u1", freqNum = 1, freqDen = 1))
        }
        val db = DesktopDatabase.open(path).also { opened.add(it) }
        assertEquals(listOf("Read"), habitNames(db))
        assertFalse(Files.exists(tmp.resolve("backups")))
    }

    @Test
    fun testOldDatabaseIsBackedUpThenUpgraded() {
        val path = tmp.resolve("uhabits.db")
        val old = JavaDatabaseOpener().open(path.toString())
        old.setVersion(8)
        runBlocking { old.migrateTo(26) { DesktopDatabase.loadMigration(it) } }
        old.run("insert into Habits(name, freq_num, freq_den, color, position, archived, type, uuid, description, question, unit) values ('Old', 1, 1, 0, 0, 0, 0, 'old1', '', '', '')")
        old.close()

        val db = DesktopDatabase.open(path).also { opened.add(it) }
        assertEquals(DATABASE_VERSION, db.getVersion())
        assertEquals(listOf("Old"), habitNames(db))
        val backups = Files.list(tmp.resolve("backups")).use { it.toList() }
        assertEquals(1, backups.size)
        assertTrue(backups[0].fileName.toString().startsWith("Before upgrade from v26"))
    }

    @Test
    fun testNewerDatabaseIsRefused() {
        val path = tmp.resolve("uhabits.db")
        val db = JavaDatabaseOpener().open(path.toString())
        db.setVersion(DATABASE_VERSION + 1)
        db.close()
        val error = runCatching { DesktopDatabase.open(path) }.exceptionOrNull()
        assertTrue(error is UnsupportedDatabaseException)
    }

    @Test
    fun testSyncFolderStoresGzipAndLeavesNoTempFiles() {
        val folder = FileSyncFolder(tmp.resolve("shared"))
        folder.write("x.sync.gz", "hello\nworld")
        assertEquals("hello\nworld", folder.read("x.sync.gz"))
        assertEquals(listOf("x.sync.gz"), folder.listFiles())
        GZIPInputStream(Files.newInputStream(folder.dir.resolve("x.sync.gz"))).use { }
        folder.write("x.sync.gz", "second")
        assertEquals("second", folder.read("x.sync.gz"))
        assertNull(folder.read("missing.sync.gz"))
    }

    @Test
    fun testCorruptFileReadsAsNull() {
        val folder = FileSyncFolder(tmp.resolve("shared"))
        Files.write(folder.dir.resolve("bad.sync.gz"), byteArrayOf(1, 2, 3))
        assertNull(folder.read("bad.sync.gz"))
    }

    @Test
    fun testTwoDesktopsSyncThroughAFolder() {
        val shared = FileSyncFolder(tmp.resolve("shared"))
        val dbA = open("a/uhabits.db")
        val dbB = open("b/uhabits.db")
        val a = DesktopSync(dbA, shared)
        val b = DesktopSync(dbB, shared)

        val habits = HabitRepository(dbA, a.sync)
        val id = habits.insert(HabitData(name = "Read", uuid = "u1", freqNum = 1, freqDen = 1))
        EntryRepository(dbA, a.sync).insert(EntryData(habitId = id, timestamp = 5 * 86_400_000L, value = 2))

        assertTrue(a.syncOnce().wroteOwnFile)
        val report = b.syncOnce()
        assertEquals(1, report.peersMerged)
        assertEquals(listOf("Read"), habitNames(dbB))
        assertEquals(1L, dbB.queryLong("select count(*) from Repetitions"))
        assertEquals(2, shared.listFiles().count { it.endsWith(SyncEngine.FILE_SUFFIX) })
    }

    @Test
    fun testWatcherPicksUpAPeerFile() {
        val shared = FileSyncFolder(tmp.resolve("shared"))
        val dbA = open("a/uhabits.db")
        val dbB = open("b/uhabits.db")
        val a = DesktopSync(dbA, shared)
        val b = DesktopSync(dbB, shared)
        HabitRepository(dbA, a.sync).insert(HabitData(name = "Read", uuid = "u1", freqNum = 1, freqDen = 1))

        FolderWatcher(b, shared.dir, debounceMillis = 100, intervalSeconds = 60).use {
            Thread.sleep(500)
            a.syncOnce()
            val deadline = System.currentTimeMillis() + 10_000
            while (habitNames(dbB).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals(listOf("Read"), habitNames(dbB))
        }
    }

    private inline fun <T> Database.use(block: (Database) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
