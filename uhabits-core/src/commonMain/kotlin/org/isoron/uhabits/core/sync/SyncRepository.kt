package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.query
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import kotlin.random.Random

data class SyncMetaRecord(val key: String, val hlc: String, val deleted: Boolean)

/**
 * Access to the sync bookkeeping tables (`SyncMeta`, `SyncState`).
 *
 * Every habit is a key `h:<habitUuid>` and every entry a key `e:<habitUuid>:<epochDay>`. Each key
 * carries the stamp of its last change and whether that change was a deletion. The habit and entry
 * repositories call [touchHabit], [touchEntry], [deleteHabit] and [deleteEntry] on every write.
 * Wrap remote changes in [suppressed] so they are not stamped again as local edits.
 */
class SyncRepository(
    private val db: Database,
    nowMillis: () -> Long = SyncClock.defaultNowMillis
) {
    private var suppressDepth = 0

    val isSuppressed: Boolean get() = suppressDepth > 0

    val deviceId: String = getState(DEVICE_ID) ?: newDeviceId().also { setState(DEVICE_ID, it) }

    val clock = SyncClock(deviceId, nowMillis, initial = getState(LAST_HLC))

    fun <T> suppressed(block: () -> T): T {
        suppressDepth++
        try {
            return block()
        } finally {
            suppressDepth--
        }
    }

    fun habitKey(uuid: String) = "h:$uuid"

    fun entryKey(uuid: String, timestamp: Long) = "e:$uuid:${timestamp / MILLIS_PER_DAY}"

    fun touchHabit(habitId: Long) {
        if (isSuppressed) return
        habitUuid(habitId)?.let { stamp(habitKey(it), deleted = false) }
    }

    /** Stamps every habit whose position is in [from]..[to]. Used after bulk position shifts. */
    fun touchHabitsInPositions(from: Int, to: Int) {
        if (isSuppressed) return
        val uuids = mutableListOf<String>()
        db.query("select uuid from Habits where position >= $from and position <= $to") { stmt ->
            stmt.getTextOrNull(0)?.let { uuids.add(it) }
        }
        for (uuid in uuids) stamp(habitKey(uuid), deleted = false)
    }

    fun deleteHabit(uuid: String?) {
        if (isSuppressed || uuid == null) return
        stamp(habitKey(uuid), deleted = true)
    }

    fun touchEntry(habitId: Long, timestamp: Long) {
        if (isSuppressed) return
        habitUuid(habitId)?.let { stamp(entryKey(it, timestamp), deleted = false) }
    }

    fun deleteEntry(habitId: Long, timestamp: Long) {
        if (isSuppressed) return
        habitUuid(habitId)?.let { stamp(entryKey(it, timestamp), deleted = true) }
    }

    fun deleteAllEntries(habitId: Long) {
        if (isSuppressed) return
        val uuid = habitUuid(habitId) ?: return
        val timestamps = mutableListOf<Long>()
        db.query("select timestamp from Repetitions where habit = $habitId") { stmt ->
            timestamps.add(stmt.getLong(0))
        }
        for (ts in timestamps) stamp(entryKey(uuid, ts), deleted = true)
    }

    fun habitUuid(habitId: Long): String? =
        db.querySingle("select uuid from Habits where id = $habitId") { it.getTextOrNull(0) }

    fun habitId(uuid: String): Long? =
        db.querySingle("select id from Habits where uuid = ?", uuid) { it.getLong(0) }

    /**
     * Stamps every habit and entry that has no live stamp yet, such as rows that existed before
     * sync was enabled or that came in through a restored backup. Returns how many were stamped.
     */
    fun stampMissing(): Int {
        val keys = mutableListOf<String>()
        db.query(
            """select h.uuid from Habits h left join SyncMeta m on m.key = 'h:' || h.uuid
               where h.uuid is not null and (m.key is null or m.deleted = 1)"""
        ) { keys.add(habitKey(it.getText(0))) }
        db.query(
            """select h.uuid, r.timestamp from Repetitions r join Habits h on h.id = r.habit
               left join SyncMeta m on m.key = 'e:' || h.uuid || ':' || (r.timestamp / $MILLIS_PER_DAY)
               where h.uuid is not null and (m.key is null or m.deleted = 1)"""
        ) { keys.add(entryKey(it.getText(0), it.getLong(1))) }
        if (keys.isEmpty()) return 0
        // One transaction: thousands of separate commits would take minutes on a phone.
        db.begin()
        try {
            for (key in keys) put(key, clock.next(), deleted = false)
            setState(LAST_HLC, clock.last()!!)
            db.commit()
        } catch (e: Throwable) {
            db.run("ROLLBACK")
            throw e
        }
        return keys.size
    }

    /** Writes [key] with a fresh stamp from the local clock. */
    fun stamp(key: String, deleted: Boolean) {
        put(key, clock.next(), deleted)
        setState(LAST_HLC, clock.last()!!)
    }

    /** Stores a stamp received from another device, keeping the local clock ahead of it. */
    fun applyRemote(key: String, hlc: String, deleted: Boolean) {
        clock.observe(hlc)
        put(key, hlc, deleted)
        setState(LAST_HLC, clock.last()!!)
    }

    fun get(key: String): SyncMetaRecord? =
        db.querySingle("select key, hlc, deleted from SyncMeta where key = ?", key) { stmt ->
            SyncMetaRecord(stmt.getText(0), stmt.getText(1), stmt.getInt(2) != 0)
        }

    fun all(): List<SyncMetaRecord> {
        val result = mutableListOf<SyncMetaRecord>()
        db.query("select key, hlc, deleted from SyncMeta order by key") { stmt ->
            result.add(SyncMetaRecord(stmt.getText(0), stmt.getText(1), stmt.getInt(2) != 0))
        }
        return result
    }

    fun getState(name: String): String? =
        db.querySingle("select value from SyncState where name = ?", name) { it.getText(0) }

    fun setState(name: String, value: String) {
        db.run("insert or replace into SyncState(name, value) values (?, ?)") {
            bindText(1, name)
            bindText(2, value)
        }
    }

    private fun put(key: String, hlc: String, deleted: Boolean) {
        db.run("insert or replace into SyncMeta(key, hlc, deleted) values (?, ?, ?)") {
            bindText(1, key)
            bindText(2, hlc)
            bindInt(3, if (deleted) 1 else 0)
        }
    }

    companion object {
        const val DEVICE_ID = "device_id"
        const val LAST_HLC = "last_hlc"
        const val MILLIS_PER_DAY = 86_400_000L

        private fun newDeviceId(): String {
            val hex = "0123456789abcdef"
            return buildString {
                for (b in Random.nextBytes(6)) {
                    append(hex[(b.toInt() shr 4) and 0xF])
                    append(hex[b.toInt() and 0xF])
                }
            }
        }
    }
}
