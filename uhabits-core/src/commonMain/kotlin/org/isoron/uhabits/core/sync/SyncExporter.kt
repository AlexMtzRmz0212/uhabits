package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.query
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.database.HabitRepository

/** Builds the snapshot of this device's habits, entries and deletions that goes in its sync file. */
class SyncExporter(private val db: Database, private val sync: SyncRepository) {
    fun snapshot(): SyncSnapshot {
        sync.stampMissing()
        val meta = sync.all().associateBy { it.key }
        val records = mutableListOf<SyncRecord>()

        for (habit in HabitRepository(db).findAll()) {
            val uuid = habit.uuid ?: continue
            val key = sync.habitKey(uuid)
            val m = meta[key] ?: continue
            records.add(HabitRecord(key, m.hlc, habit))
        }

        db.query(
            """select h.uuid, r.timestamp, r.value, r.notes
               from Repetitions r join Habits h on h.id = r.habit where h.uuid is not null"""
        ) { stmt ->
            val uuid = stmt.getText(0)
            val timestamp = stmt.getLong(1)
            val key = sync.entryKey(uuid, timestamp)
            val m = meta[key]
            if (m != null) {
                records.add(
                    EntryRecord(key, m.hlc, uuid, timestamp, stmt.getInt(2), stmt.getTextOrNull(3) ?: "")
                )
            }
        }

        for (m in meta.values) {
            if (m.deleted) records.add(TombstoneRecord(m.key, m.hlc))
        }

        return SyncSnapshot(SyncHeader(DATABASE_VERSION, SyncFile.APP_ID, sync.deviceId), records)
    }
}
