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

    /** Short text about the file on disk (size, date), for diagnosing sync problems. */
    fun describe(name: String): String? = null

    /** When the file was last changed on disk, in epoch milliseconds, or null if unknown. */
    fun lastModified(name: String): Long? = null
}

data class SyncReport(
    val peersMerged: Int,
    val applied: Int,
    val wroteOwnFile: Boolean,
    val errors: List<String>,
    /** How many other devices' files were in the folder. */
    val peersSeen: Int = 0,
    /** How many of those could not be read (for example, still being uploaded). */
    val unreadable: Int = 0,
    /** One line per other device's file: what is on disk and how much of it was read. */
    val peerInfo: List<String> = emptyList(),
    /** When the newest other-device file was last changed on disk (epoch ms), if the folder can tell. */
    val peerModified: Long? = null,
    /** When this device's own file was last changed on disk (epoch ms), if the folder can tell. */
    val ownModified: Long? = null,
    /** After writing this device's own file: whether reading it back gave the same content. Null if nothing was written. */
    val ownVerified: Boolean? = null
) {
    val changed: Boolean get() = applied > 0
}

/**
 * Runs sync rounds. [download] merges the other devices' files into this device, [upload] writes
 * this device's own file, and [syncOnce] does both. Safe to run at any time and any number of times.
 */
class SyncEngine(
    private val db: Database,
    private val sync: SyncRepository,
    private val folder: SyncFolder
) {
    private val merger = SyncMerger(db, sync)
    private val exporter = SyncExporter(db, sync)

    private val ownFileName get() = sync.deviceId + FILE_SUFFIX

    /** Merges every peer file that changed since the last round. Never writes to the folder. */
    fun download(): SyncReport {
        var peers = 0
        var applied = 0
        var seen = 0
        var unreadable = 0
        val peerInfo = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var peerModified: Long? = null

        val listing = folder.listFiles()
        peerInfo.add("folder lists ${listing.size}: " + listing.joinToString(",") { it.take(6) })
        for (name in listing.filter { it.endsWith(FILE_SUFFIX) && it != ownFileName }) {
            seen++
            folder.lastModified(name)?.let { m -> peerModified = maxOf(peerModified ?: m, m) }
            val text = folder.read(name)
            peerInfo.add("${name.take(6)}: ${folder.describe(name) ?: "?"}, read ${text?.length ?: 0} chars")
            if (text == null) {
                unreadable++
                continue
            }
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
        return SyncReport(peers, applied, false, errors, seen, unreadable, peerInfo, peerModified)
    }

    /**
     * Writes this device's own file if its content changed, or always when [force] is true, then
     * reads it back to check it really is in the folder.
     */
    fun upload(force: Boolean = false): SyncReport {
        val text = SyncFile.encode(exporter.snapshot())
        val hash = hash(text)
        val missing = ownFileName !in folder.listFiles()
        val wrote = force || missing || sync.getState(OWN_HASH) != hash
        var verified: Boolean? = null
        if (wrote) {
            folder.write(ownFileName, text)
            sync.setState(OWN_HASH, hash)
            verified = folder.read(ownFileName)?.let { hash(it) == hash } ?: false
        }
        return SyncReport(
            0,
            0,
            wrote,
            emptyList(),
            ownModified = folder.lastModified(ownFileName),
            ownVerified = verified
        )
    }

    fun syncOnce(): SyncReport {
        val down = download()
        val up = upload()
        return down.copy(
            wroteOwnFile = up.wroteOwnFile,
            ownModified = up.ownModified,
            ownVerified = up.ownVerified
        )
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
