package org.isoron.uhabits.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Small settings file (`config.properties`) kept next to the database. */
class Settings(dbPath: Path) {
    private val file: Path = dbPath.resolveSibling("config.properties")

    private fun load(): Properties {
        val props = Properties()
        if (Files.exists(file)) Files.newInputStream(file).use { props.load(it) }
        return props
    }

    private fun store(props: Properties) {
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { props.store(it, "My Habits desktop settings") }
    }

    fun savedFolder(): Path? = load().getProperty("syncFolder")?.let { Paths.get(it) }

    fun saveFolder(folder: Path) {
        store(load().apply { setProperty("syncFolder", folder.toAbsolutePath().toString()) })
    }

    /** True once the user chose to use the app without a sync folder. */
    var skippedSync: Boolean
        get() = load().getProperty("skippedSync") == "true"
        set(value) = store(load().apply { setProperty("skippedSync", value.toString()) })
}
