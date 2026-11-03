package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.query
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitRepository

data class MergeResult(val applied: Int, val ignored: Int) {
    val changed: Boolean get() = applied > 0
}

/**
 * Applies another device's snapshot to this database. For each key the record with the highest
 * stamp wins, so merging is idempotent and order-independent. Changes are written without being
 * stamped as local edits; the remote stamp is stored instead.
 *
 * The caller must reload any cached habit list and recompute scores when [MergeResult.changed].
 */
class SyncMerger(private val db: Database, private val sync: SyncRepository) {
    private val habits = HabitRepository(db, sync)
    private val entries = EntryRepository(db, sync)

    fun merge(snapshot: SyncSnapshot): MergeResult {
        val header = snapshot.header
        if (header.app != SyncFile.APP_ID) {
            throw UnsupportedSyncFileException("sync file belongs to another app: ${header.app}")
        }
        if (header.schema > DATABASE_VERSION) {
            throw UnsupportedSyncFileException(
                "sync file is from a newer version (${header.schema} > $DATABASE_VERSION), update this app first"
            )
        }
        if (header.deviceId == sync.deviceId) return MergeResult(0, 0)

        var applied = 0
        var ignored = 0
        var habitsChanged = false

        db.begin()
        try {
            sync.suppressed {
                // Habits first so entries can find their habit, tombstones last so they win ties.
                val ordered = snapshot.records.sortedBy {
                    when (it) {
                        is HabitRecord -> 0
                        is EntryRecord -> 1
                        is TombstoneRecord -> 2
                    }
                }
                for (r in ordered) {
                    val local = sync.get(r.key)
                    if (local != null && r.hlc <= local.hlc) {
                        ignored++
                        continue
                    }
                    val done = when (r) {
                        is HabitRecord -> applyHabit(r).also { habitsChanged = habitsChanged || it }
                        is EntryRecord -> applyEntry(r)
                        is TombstoneRecord -> applyTombstone(r).also { habitsChanged = habitsChanged || r.key.startsWith("h:") }
                    }
                    if (done) applied++ else ignored++
                }
                if (habitsChanged) normalizePositions()
            }
            db.commit()
        } catch (e: Throwable) {
            db.run("ROLLBACK")
            throw e
        }
        return MergeResult(applied, ignored)
    }

    private fun applyHabit(r: HabitRecord): Boolean {
        val uuid = r.data.uuid ?: return false
        val existing = sync.habitId(uuid)
        if (existing == null) {
            habits.insert(r.data.copy(id = null))
        } else {
            habits.update(r.data.copy(id = existing))
        }
        sync.applyRemote(r.key, r.hlc, deleted = false)
        return true
    }

    private fun applyEntry(r: EntryRecord): Boolean {
        // The habit is missing here if it was deleted after this entry was written elsewhere.
        val habitId = sync.habitId(r.habitUuid) ?: return false
        deleteDay(habitId, r.timestamp / SyncRepository.MILLIS_PER_DAY)
        entries.insert(EntryData(habitId = habitId, timestamp = r.timestamp, value = r.value, notes = r.notes))
        sync.applyRemote(r.key, r.hlc, deleted = false)
        return true
    }

    private fun applyTombstone(r: TombstoneRecord): Boolean {
        val parts = r.key.split(":")
        when (parts[0]) {
            "h" -> {
                val habitId = sync.habitId(parts[1])
                if (habitId != null) {
                    entries.deleteByHabitId(habitId)
                    habits.delete(habitId)
                }
            }
            "e" -> {
                val habitId = sync.habitId(parts[1])
                if (habitId != null) deleteDay(habitId, parts[2].toLong())
            }
            else -> return false
        }
        sync.applyRemote(r.key, r.hlc, deleted = true)
        return true
    }

    private fun deleteDay(habitId: Long, epochDay: Long) {
        val from = epochDay * SyncRepository.MILLIS_PER_DAY
        val to = from + SyncRepository.MILLIS_PER_DAY
        db.run("delete from Repetitions where habit = $habitId and timestamp >= $from and timestamp < $to")
    }

    /**
     * Habits from different devices can share a position. Renumber them 0..n-1, ordering ties by
     * uuid so every device reaches the same order from the same data.
     */
    private fun normalizePositions() {
        val rows = mutableListOf<Triple<Long, Int, String>>()
        db.query("select id, position, coalesce(uuid, '') from Habits") { stmt ->
            rows.add(Triple(stmt.getLong(0), stmt.getInt(1), stmt.getText(2)))
        }
        rows.sortWith(compareBy({ it.second }, { it.third }))
        for ((index, row) in rows.withIndex()) {
            if (row.second != index) db.run("update Habits set position = $index where id = ${row.first}")
        }
    }
}
