package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.format
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.platform.io.queryLong
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Version27Test : BaseUnitTest() {

    private lateinit var db: Database

    private suspend fun initDb() {
        db = openDatabaseResource("/databases/022.db")
    }

    private suspend fun migrateTo(version: Int) {
        db.migrateTo(version) { v ->
            val path = "migrations/${format("%02d.sql", v)}"
            fileOpener.openResourceFile(path).lines().joinToString("\n")
        }
    }

    private fun dbTest(block: suspend () -> Unit) = runTest {
        initDb()
        block()
    }

    @Test
    fun testMigrateTo27CreatesSyncTables() = dbTest {
        migrateTo(27)
        db.query("select key, hlc, deleted from SyncMeta") {}
        db.query("select name, value from SyncState") {}
    }

    @Test
    fun testMigrateTo27LeavesHabitsAndEntriesUntouched() = dbTest {
        migrateTo(26)
        val habits = db.queryLong("select count(*) from Habits")
        val entries = db.queryLong("select count(*) from Repetitions")
        migrateTo(27)
        assertEquals(habits, db.queryLong("select count(*) from Habits"))
        assertEquals(entries, db.queryLong("select count(*) from Repetitions"))
        assertEquals(0L, db.queryLong("select count(*) from SyncMeta"))
    }
}
