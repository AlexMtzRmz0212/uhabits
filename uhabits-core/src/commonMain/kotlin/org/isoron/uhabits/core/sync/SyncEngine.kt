package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database

/**
 * The shared folder that holds one `<deviceId>.sync.gz` file per device. [read] and [write] deal in
 * the uncompressed text; implementations gzip it on disk.
 */
interface SyncFolder {
    fun listFiles(): List<String>
    fun read(name: String): String?
    fun write(name: String, text: String)
}

data class SyncReport(
    val peersMerged: Int,
    val applied: Int,
    val wroteOwnFile: Boolean,
    val errors: List<String>
) {
    val changed: Boolean get() = applied > 0
}

/**
 * Runs one sync round: merge every peer file that changed since the last round, then write this
 * device's own file if its content changed. Safe to run at any time and any number of times.
 */
class SyncEngine(
    private val db: Database,
    private val sync: SyncRepository,
    private val folder: SyncFolder
) {
    private val merger = SyncMerger(db, sync)
    private val exporter = SyncExporter(db, sync)

    private val ownFileName get() = sync.deviceId + FILE_SUFFIX

    fun syncOnce(): SyncReport {
        var peers = 0
        var applied = 0
        val errors = mutableListOf<String>()

        for (name in folder.listFiles().filter { it.endsWith(FILE_SUFFIX) && it != ownFileName }) {
            val text = folder.read(name) ?: continue
            val hash = hash(text)
            if (sync.getState(PEER_HASH + name) == hash) continue
            try {
                applied += merger.merge(SyncFile.decode(text)).applied
                sync.setState(PEER_HASH + name, hash)
                peers++
            } catch (e: UnsupportedSyncFileException) {
                errors.add("$name: ${e.message}")
            }
        }

        val text = SyncFile.encode(exporter.snapshot())
        val hash = hash(text)
        val missing = ownFileName !in folder.listFiles()
        val wrote = missing || sync.getState(OWN_HASH) != hash
        if (wrote) {
            folder.write(ownFileName, text)
            sync.setState(OWN_HASH, hash)
        }
        return SyncReport(peers, applied, wrote, errors)
    }

    companion object {
        const val FILE_SUFFIX = ".sync.gz"
        private const val PEER_HASH = "peer_hash:"
        private const val OWN_HASH = "own_hash"

        /** 64-bit FNV-1a, enough to tell whether a file changed. */
        fun hash(text: String): String {
            var h = -0x340d631b7bdddcdbL
            for (c in text) {
                h = (h xor c.code.toLong()) * 0x100000001b3L
            }
            return h.toULong().toString(16)
        }
    }
}
