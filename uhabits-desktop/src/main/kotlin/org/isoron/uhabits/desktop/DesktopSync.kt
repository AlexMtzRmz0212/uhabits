package org.isoron.uhabits.desktop

import org.isoron.platform.io.Database
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.SyncFolder
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.sync.SyncRepository
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs sync rounds for one database and one folder, one at a time. Share [lock] with any other code
 * that uses the same database connection.
 */
class DesktopSync(db: Database, folder: SyncFolder, private val lock: Any = Any()) {
    val sync = SyncRepository(db)
    private val engine = SyncEngine(db, sync, folder)

    val ownFileName: String get() = sync.deviceId + SyncEngine.FILE_SUFFIX

    fun syncOnce(): SyncReport = synchronized(lock) { engine.syncOnce() }
}

/**
 * Keeps syncing in the background: once right away, again whenever a peer's file appears or
 * changes in the folder (file-sync tools deliver files in bursts, so changes are batched), and at
 * least every [intervalSeconds] in case a change notification was missed.
 */
class FolderWatcher(
    private val desktop: DesktopSync,
    private val dir: Path,
    private val intervalSeconds: Long = 120,
    private val debounceMillis: Long = 1500,
    private val onReport: (SyncReport) -> Unit = {},
    private val onError: (Exception) -> Unit = {}
) : AutoCloseable {
    private val watchService = dir.fileSystem.newWatchService()

    @Volatile
    private var running = true

    private val worker = thread(name = "folder-watcher", isDaemon = true) { loop() }

    private fun round() {
        try {
            onReport(desktop.syncOnce())
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun loop() {
        dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY)
        round()
        try {
            while (running) {
                val key = watchService.poll(intervalSeconds, TimeUnit.SECONDS)
                if (!running) break
                if (key == null) {
                    round()
                    continue
                }
                var relevant = isRelevant(key.pollEvents().map { it.context().toString() })
                key.reset()
                // Wait for the burst to end, then handle everything that arrived meanwhile.
                Thread.sleep(debounceMillis)
                while (true) {
                    val more = watchService.poll() ?: break
                    relevant = isRelevant(more.pollEvents().map { it.context().toString() }) || relevant
                    more.reset()
                }
                if (relevant) round()
            }
        } catch (e: ClosedWatchServiceException) {
            // closed on purpose
        } catch (e: InterruptedException) {
            // closed on purpose
        }
    }

    private fun isRelevant(names: List<String>): Boolean =
        names.any { it.endsWith(SyncEngine.FILE_SUFFIX) && it != desktop.ownFileName }

    /** Stops watching and runs a last round so changes made just now are shared. */
    override fun close() {
        running = false
        watchService.close()
        worker.interrupt()
        worker.join(5000)
        round()
    }
}
