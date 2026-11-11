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
import org.isoron.uhabits.core.sync.SyncSummary
import org.isoron.uhabits.utils.DatabaseUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    /** What the last upload did, with its time, for the settings screen. */
    val uploadStatus: String? get() = prefs.getString(PREF_UP_STATUS, null)

    /** What the last download did, with its time, for the settings screen. */
    val downloadStatus: String? get() = prefs.getString(PREF_DOWN_STATUS, null)

    fun syncNow(): String = runRound(Direction.BOTH)

    /** Runs one upload, download or full round and returns a short text about it, for a toast. */
    fun runRound(direction: Direction): String = synchronized(lock) {
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
            val now = System.currentTimeMillis()
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val format = { t: Long -> clock.format(t) }
            // A manual upload always rewrites the file, so Drive sees a fresh change to send.
            val report = when (direction) {
                Direction.UPLOAD -> engine.upload(force = true)
                Direction.DOWNLOAD -> engine.download()
                Direction.BOTH -> engine.syncOnce()
            }
            Log.i(TAG, "Sync done ($direction): $report")

            if (report.changed) refreshAfterMerge(component.habitList as SQLiteHabitList)
            val up = SyncSummary.describeUpload(report, now, format)
            val down = SyncSummary.describeDownload(report, now, format)
            val stamp = clock.format(now)
            if (direction != Direction.DOWNLOAD) saveStatus(PREF_UP_STATUS, "$stamp · $up")
            if (direction != Direction.UPLOAD) saveStatus(PREF_DOWN_STATUS, "$stamp · $down")
            summary = when (direction) {
                Direction.UPLOAD -> up
                Direction.DOWNLOAD -> down
                Direction.BOTH -> "Upload: $up\nDownload: $down"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            val message = "Failed: ${e.message ?: e.javaClass.simpleName}"
            summary = message
            if (direction != Direction.DOWNLOAD) saveStatus(PREF_UP_STATUS, message)
            if (direction != Direction.UPLOAD) saveStatus(PREF_DOWN_STATUS, message)
        } finally {
            // An Error (such as out of memory) skips the handlers above; never leave "Syncing…" stuck.
            prefs.edit().putBoolean(PREF_RUNNING, false).apply()
        }
        summary
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

    private fun saveStatus(key: String, result: String) {
        prefs.edit().putString(key, result).putBoolean(PREF_RUNNING, false).apply()
    }

    /** Which half of a round to run: only send, only receive, or both. */
    enum class Direction { UPLOAD, DOWNLOAD, BOTH }

    companion object {
        const val PREF_FOLDER = "syncFolder"
        const val PREF_UP_STATUS = "syncUploadStatus"
        const val PREF_DOWN_STATUS = "syncDownloadStatus"
        const val PREF_RUNNING = "syncRunning"
        private const val FIRST_SYNC_BACKUP = "first_sync_backup"
        private const val TAG = "SyncManager"
        private const val JOB_ID = 4271

        private val lock = Any()
        private val executor = Executors.newSingleThreadExecutor()
        private val lastStart = AtomicLong(0)
        private val soonScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        private var pendingSoon: ScheduledFuture<*>? = null

        /**
         * Shares a local edit a few seconds from now. Another edit inside the delay restarts the
         * wait, so a burst of taps is sent once.
         */
        @Synchronized
        fun requestSyncSoon(context: Context, delayMillis: Long = 5_000) {
            val manager = SyncManager(context)
            if (!manager.isEnabled) return
            pendingSoon?.cancel(false)
            pendingSoon = soonScheduler.schedule(
                { executor.execute { manager.syncNow() } },
                delayMillis,
                TimeUnit.MILLISECONDS
            )
        }

        /** Runs a round in the background unless one started less than [minGapMillis] ago. */
        fun requestSync(context: Context, minGapMillis: Long = 30_000) {
            val manager = SyncManager(context)
            if (!manager.isEnabled) return
            if (System.currentTimeMillis() - lastStart.get() < minGapMillis) return
            executor.execute { manager.syncNow() }
        }

        /** Runs a round right now in the background and calls [onDone] on the main thread with a short summary. */
        fun syncWithResult(context: Context, direction: Direction = Direction.BOTH, onDone: (String) -> Unit) {
            val manager = SyncManager(context)
            executor.execute {
                val summary = try {
                    manager.runRound(direction)
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
