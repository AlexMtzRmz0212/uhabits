package org.isoron.uhabits.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.utils.DatabaseUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs sync rounds against the folder chosen in settings. All rounds run one at a time on a
 * background thread. When another device's changes were applied, the habit list is reloaded and
 * the screens, widgets and reminders are refreshed.
 */
class SyncManager(context: Context) {
    private val context = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(this.context)

    val folderUri: Uri?
        get() = prefs.getString(PREF_FOLDER, null)?.let { Uri.parse(it) }

    val isEnabled: Boolean get() = folderUri != null

    /** Short text for the settings screen, such as "Last sync: 10:42" or "Failed: ...". */
    val statusText: String?
        get() = prefs.getString(PREF_STATUS, null)

    /** Runs one round and returns a short sentence about it, for a toast. */
    fun syncNow(): String = synchronized(lock) {
        val uri = folderUri ?: return@synchronized "No sync folder"
        var summary = ""
        lastStart.set(System.currentTimeMillis())
        prefs.edit().putBoolean(PREF_RUNNING, true).apply()
        try {
            val component = HabitsApplication.component
            val factory = component.modelFactory as SQLModelFactory
            val sync = factory.syncRepository ?: error("Database has no sync tables")

            if (sync.getState(FIRST_SYNC_BACKUP) == null) {
                SafetyBackup.create(context, DatabaseUtils.getDatabaseFile(context), "Before first sync")
                sync.setState(FIRST_SYNC_BACKUP, "done")
            }

            val engine = SyncEngine(factory.database, sync, DocumentFileSyncFolder(context, uri))
            val report = engine.syncOnce()
            Log.i(TAG, "Sync done: $report")

            if (report.changed) refreshAfterMerge(component.habitList as SQLiteHabitList)
            saveStatus(
                describe(report)
            )
            summary = when {
                report.changed -> "Synced: got ${report.applied} changes"
                report.peersSeen == 0 -> "Up to date, but no other device's file found yet"
                else -> "Already up to date"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            summary = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
            saveStatus("Failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // An Error (such as out of memory) skips the handlers above; never leave "Syncing…" stuck.
            prefs.edit().putBoolean(PREF_RUNNING, false).apply()
        }
        summary
    }

    /** What happened, in words the settings screen can show: it must say if the other device's file was not found. */
    private fun describe(report: org.isoron.uhabits.core.sync.SyncReport): String {
        val parts = mutableListOf<String>()
        parts.add(if (report.changed) "got ${report.applied} changes" else "up to date")
        parts.add(
            when (report.peersSeen) {
                0 -> "no other device's file found"
                1 -> "1 other file seen"
                else -> "${report.peersSeen} other files seen"
            }
        )
        if (report.unreadable > 0) parts.add("${report.unreadable} could not be read")
        parts.addAll(report.peerInfo)
        report.errors.forEach { parts.add("skipped $it") }
        return parts.joinToString(", ")
    }

    private fun refreshAfterMerge(habitList: SQLiteHabitList) {
        habitList.reloadAndRecompute()
        Handler(Looper.getMainLooper()).post {
            habitList.observable.notifyListeners()
            val component = HabitsApplication.component
            component.widgetUpdater.updateWidgets()
            component.reminderScheduler.scheduleAll()
        }
    }

    private fun saveStatus(result: String) {
        prefs.edit()
            .putLong(PREF_LAST_TIME, System.currentTimeMillis())
            .putString(PREF_STATUS, result)
            .putBoolean(PREF_RUNNING, false)
            .apply()
    }

    companion object {
        const val PREF_FOLDER = "syncFolder"
        const val PREF_LAST_TIME = "syncLastTime"
        const val PREF_STATUS = "syncStatus"
        const val PREF_RUNNING = "syncRunning"
        private const val FIRST_SYNC_BACKUP = "first_sync_backup"
        private const val TAG = "SyncManager"
        private const val JOB_ID = 4271

        private val lock = Any()
        private val executor = Executors.newSingleThreadExecutor()
        private val lastStart = AtomicLong(0)

        /** Runs a round in the background unless one started less than [minGapMillis] ago. */
        fun requestSync(context: Context, minGapMillis: Long = 30_000) {
            val manager = SyncManager(context)
            if (!manager.isEnabled) return
            if (System.currentTimeMillis() - lastStart.get() < minGapMillis) return
            executor.execute { manager.syncNow() }
        }

        /** Runs a round right now in the background and calls [onDone] on the main thread with a short summary. */
        fun syncWithResult(context: Context, onDone: (String) -> Unit) {
            val manager = SyncManager(context)
            executor.execute {
                val summary = try {
                    manager.syncNow()
                } catch (e: Throwable) {
                    "Sync failed: ${e.message ?: e.javaClass.simpleName}"
                }
                Handler(Looper.getMainLooper()).post { onDone(summary) }
            }
        }

        /** Keeps the periodic background job in line with whether a folder is selected. */
        fun updateSchedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!SyncManager(context).isEnabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            scheduler.schedule(job)
        }
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            SyncManager(applicationContext).syncNow()
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
