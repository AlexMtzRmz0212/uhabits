package org.isoron.uhabits.desktop

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class UnsupportedDatabaseException(message: String) : Exception(message)

object DesktopDatabase {
    /** `%APPDATA%\MyHabits` on Windows, `~/.myhabits` elsewhere. */
    fun defaultDir(): Path {
        val appData = System.getenv("APPDATA")
        return if (appData != null) {
            Paths.get(appData, "MyHabits")
        } else {
            Paths.get(System.getProperty("user.home"), ".myhabits")
        }
    }

    fun defaultPath(): Path = defaultDir().resolve("uhabits.db")

    /**
     * Opens the database at [path], creating it if needed and upgrading it to the current
     * version. An existing file is copied to a `backups` folder next to it before any upgrade.
     */
    fun open(path: Path): Database {
        path.parent?.let { Files.createDirectories(it) }
        val isNew = !Files.exists(path)
        val db = JavaDatabaseOpener().open(path.toString())
        if (isNew) {
            // The same starting point the Android app uses for a new database.
            db.setVersion(8)
        } else {
            val version = db.getVersion()
            if (version < 8) {
                db.close()
                throw UnsupportedDatabaseException("$path is too old to upgrade (version $version)")
            }
            if (version > DATABASE_VERSION) {
                db.close()
                throw UnsupportedDatabaseException(
                    "$path is from a newer version of the app ($version > $DATABASE_VERSION). Update this app first."
                )
            }
            if (version < DATABASE_VERSION) backupBeforeUpgrade(path, version)
        }
        runBlocking { db.migrateTo(DATABASE_VERSION) { v -> loadMigration(v) } }
        return db
    }

    fun loadMigration(version: Int): String {
        val name = "/migrations/%02d.sql".format(version)
        val stream = DesktopDatabase::class.java.getResourceAsStream(name)
            ?: throw IllegalStateException("Missing migration $name")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun backupBeforeUpgrade(path: Path, version: Int) {
        val dir = path.resolveSibling("backups")
        Files.createDirectories(dir)
        val stamp = System.currentTimeMillis()
        Files.copy(path, dir.resolve("Before upgrade from v$version $stamp.db"), StandardCopyOption.REPLACE_EXISTING)
    }
}
