package org.isoron.uhabits.core.sync

import org.isoron.uhabits.core.database.HabitData

class UnsupportedSyncFileException(message: String) : Exception(message)

data class SyncHeader(val schema: Int, val app: String, val deviceId: String)

sealed class SyncRecord {
    abstract val key: String
    abstract val hlc: String
}

/** A live habit. The local database id is not shared; devices match habits by `uuid`. */
data class HabitRecord(override val key: String, override val hlc: String, val data: HabitData) : SyncRecord()

data class EntryRecord(
    override val key: String,
    override val hlc: String,
    val habitUuid: String,
    val timestamp: Long,
    val value: Int,
    val notes: String
) : SyncRecord()

/** A habit or entry that was deleted. Kept so the deletion reaches devices that still have it. */
data class TombstoneRecord(override val key: String, override val hlc: String) : SyncRecord()

data class SyncSnapshot(val header: SyncHeader, val records: List<SyncRecord>)

/**
 * Text format of a device's sync file: one JSON object per line. The first line is the header,
 * the rest are records. Records are sorted by key so the same state always gives the same text.
 */
object SyncFile {
    const val APP_ID = "myhabits"

    fun encode(snapshot: SyncSnapshot): String {
        val sb = StringBuilder()
        val h = snapshot.header
        sb.append(
            MiniJson.encode(mapOf("t" to "header", "schema" to h.schema, "app" to h.app, "device" to h.deviceId))
        ).append('\n')
        for (r in snapshot.records.sortedBy { it.key }) {
            sb.append(MiniJson.encode(toMap(r))).append('\n')
        }
        return sb.toString()
    }

    fun decode(text: String): SyncSnapshot {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw UnsupportedSyncFileException("empty sync file")
        val head = parse(lines[0])
        if (head["t"] != "header") throw UnsupportedSyncFileException("missing header")
        val header = SyncHeader(
            schema = long(head, "schema").toInt(),
            app = str(head, "app"),
            deviceId = str(head, "device")
        )
        val records = lines.drop(1).map { fromMap(parse(it)) }
        return SyncSnapshot(header, records)
    }

    private fun parse(line: String): Map<String, Any?> =
        try {
            MiniJson.decode(line)
        } catch (e: IllegalArgumentException) {
            throw UnsupportedSyncFileException("malformed line: ${e.message}")
        }

    private fun toMap(r: SyncRecord): Map<String, Any?> = when (r) {
        is TombstoneRecord -> mapOf("t" to "tombstone", "key" to r.key, "hlc" to r.hlc)
        is EntryRecord -> mapOf(
            "t" to "entry", "key" to r.key, "hlc" to r.hlc, "habit" to r.habitUuid,
            "ts" to r.timestamp, "value" to r.value, "notes" to r.notes
        )
        is HabitRecord -> {
            val d = r.data
            mapOf(
                "t" to "habit", "key" to r.key, "hlc" to r.hlc,
                "name" to d.name, "description" to d.description, "question" to d.question,
                "freqNum" to d.freqNum, "freqDen" to d.freqDen, "color" to d.color,
                "position" to d.position,
                "reminderHour" to d.reminderHour, "reminderMin" to d.reminderMin,
                "reminderDays" to d.reminderDays, "highlight" to d.highlight,
                "archived" to d.archived, "type" to d.type,
                "targetValue" to d.targetValue, "targetType" to d.targetType,
                "unit" to d.unit, "uuid" to d.uuid, "category" to d.category
            )
        }
    }

    private fun fromMap(m: Map<String, Any?>): SyncRecord = when (m["t"]) {
        "tombstone" -> TombstoneRecord(str(m, "key"), str(m, "hlc"))
        "entry" -> EntryRecord(
            str(m, "key"), str(m, "hlc"), str(m, "habit"), long(m, "ts"),
            long(m, "value").toInt(), str(m, "notes")
        )
        "habit" -> HabitRecord(
            str(m, "key"),
            str(m, "hlc"),
            HabitData(
                name = str(m, "name"),
                description = str(m, "description"),
                question = str(m, "question"),
                freqNum = long(m, "freqNum").toInt(),
                freqDen = long(m, "freqDen").toInt(),
                color = long(m, "color").toInt(),
                position = long(m, "position").toInt(),
                reminderHour = (m["reminderHour"] as? Number)?.toInt(),
                reminderMin = (m["reminderMin"] as? Number)?.toInt(),
                reminderDays = long(m, "reminderDays").toInt(),
                highlight = long(m, "highlight").toInt(),
                archived = long(m, "archived").toInt(),
                type = long(m, "type").toInt(),
                targetValue = dbl(m, "targetValue"),
                targetType = long(m, "targetType").toInt(),
                unit = str(m, "unit"),
                uuid = str(m, "uuid"),
                category = str(m, "category")
            )
        )
        else -> throw UnsupportedSyncFileException("unknown record type: ${m["t"]}")
    }

    private fun str(m: Map<String, Any?>, k: String): String =
        m[k] as? String ?: throw UnsupportedSyncFileException("missing field: $k")

    private fun long(m: Map<String, Any?>, k: String): Long =
        (m[k] as? Number)?.toLong() ?: throw UnsupportedSyncFileException("missing field: $k")

    private fun dbl(m: Map<String, Any?>, k: String): Double =
        (m[k] as? Number)?.toDouble() ?: throw UnsupportedSyncFileException("missing field: $k")
}
