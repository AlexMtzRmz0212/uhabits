package org.isoron.uhabits.sync

import android.content.Context
import android.util.Log
import org.isoron.platform.time.DateFormats.Companion.getBackupDateFormat
import java.io.File

/**
 * Copies the database file into the app's private `SafetyBackups` folder before a step that cannot
 * be undone, such as the schema upgrade that adds the sync tables or the first merge. These copies
 * are not rotated or uploaded; they are only there for manual recovery.
 */
object SafetyBackup {
    fun create(context: Context, source: File, label: String): File? {
        return try {
            val dir = File(context.filesDir, "SafetyBackups").apply { mkdirs() }
            val date = getBackupDateFormat().format(System.currentTimeMillis())
            val target = File(dir, "$label $date.db")
            source.copyTo(target, overwrite = true)
            Log.i("SafetyBackup", "Wrote $target")
            target
        } catch (e: Exception) {
            Log.e("SafetyBackup", "Cannot back up database before $label", e)
            null
        }
    }
}
