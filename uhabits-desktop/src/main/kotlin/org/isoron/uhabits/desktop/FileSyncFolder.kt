package org.isoron.uhabits.desktop

import org.isoron.uhabits.core.sync.SyncFolder
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The sync folder on disk: a `LoopSync` subfolder of [root], the same layout the phone uses.
 * Files are gzip-compressed and written through a temporary name, so another device (or a
 * file-sync tool) never sees a half-written file.
 */
class FileSyncFolder(root: Path) : SyncFolder {
    val dir: Path = root.resolve(SUBFOLDER)

    init {
        Files.createDirectories(dir)
    }

    override fun listFiles(): List<String> =
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
        }

    override fun read(name: String): String? {
        val file = dir.resolve(name)
        if (!Files.exists(file)) return null
        return try {
            GZIPInputStream(Files.newInputStream(file)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            // Most likely another device is still writing the file. Try again next round.
            null
        }
    }

    override fun lastModified(name: String): Long? {
        val file = dir.resolve(name)
        return if (Files.exists(file)) Files.getLastModifiedTime(file).toMillis() else null
    }

    override fun write(name: String, text: String) {
        val temp = dir.resolve("$name.tmp")
        GZIPOutputStream(Files.newOutputStream(temp)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val target = dir.resolve(name)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val SUBFOLDER = "LoopSync"
    }
}
