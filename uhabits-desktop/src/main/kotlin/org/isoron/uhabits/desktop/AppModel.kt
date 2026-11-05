package org.isoron.uhabits.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.getFirstWeekday
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.HabitPin
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.OverviewCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCartPresenter
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.models.TimeOfDay
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.ui.views.LightTheme
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class CellKind { YES, YES_AUTO, NO, SKIP, UNKNOWN, VALUE }

data class Cell(val date: LocalDate, val kind: CellKind, val text: String)

data class HabitRow(
    val id: Long,
    val name: String,
    val category: String,
    val type: HabitType,
    val color: Int,
    val score: Double,
    val unit: String,
    val cells: List<Cell>,
    val archived: Boolean = false
)

data class StreakInfo(val start: LocalDate, val end: LocalDate, val length: Int)

data class ScoreBar(val date: LocalDate, val value: Double)

enum class DayKind { ON, OFF, DIMMED, GREY, HATCHED }

data class DetailData(
    val id: Long,
    val name: String,
    val category: String,
    val question: String,
    val type: HabitType,
    val color: Int,
    val targetText: String?,
    val scoreToday: Double,
    val monthDiff: Double,
    val yearDiff: Double,
    val totalCount: Long,
    /** Oldest first. */
    val scores: List<ScoreBar>,
    /** Newest first: index 0 is today. */
    val history: List<DayKind>,
    val today: LocalDate,
    val firstWeekday: DayOfWeek,
    val streaks: List<StreakInfo>
)

/** The fields of the create and edit dialog. */
data class EditorData(
    val name: String = "",
    val question: String = "",
    val description: String = "",
    val category: String = "",
    val colorIndex: Int = 8,
    val type: HabitType = HabitType.YES_NO,
    val freqNum: Int = 1,
    val freqDen: Int = 1,
    val unit: String = "",
    val targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    val targetValue: Double = 0.0,
    val pin: HabitPin = HabitPin.NONE
)

/**
 * Everything the window needs: the database, the habit list and the sync. All reads and writes of
 * the habit models, and every sync round, take [lock], because the database connection and the
 * cached models are not safe to use from two threads at once. The window only ever sees the
 * immutable [HabitRow] snapshots from [rows].
 */
class AppModel(val dbPath: Path) {
    val lock = Any()
    private val db = DesktopDatabase.open(dbPath)
    private val factory = SQLModelFactory(db)
    private val habitList = factory.buildHabitList() as SQLiteHabitList
    private val settings = Settings(dbPath)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sync-scheduler").apply { isDaemon = true } }
    private var desktop: DesktopSync? = null
    private var watcher: FolderWatcher? = null
    private var pendingSync: ScheduledFuture<*>? = null

    /** Changes whenever the habit data may have changed, so the window knows to redraw. */
    var revision by mutableStateOf(0)
        private set

    /** Text typed in the search box of the main list. */
    var searchQuery by mutableStateOf("")

    /** List filters, like the phone's "Show archived" and "Show completed" menu items. */
    var showArchived by mutableStateOf(false)
    var showEntered by mutableStateOf(true)

    var folder by mutableStateOf(settings.savedFolder())
        private set

    var skippedSync by mutableStateOf(settings.skippedSync)
        private set

    var syncing by mutableStateOf(false)
        private set

    var syncStatus by mutableStateOf("Not syncing")
        private set

    var error by mutableStateOf<String?>(null)

    /** A newer installer found on GitHub, or null. Never set when running from source. */
    var update by mutableStateOf<UpdateInfo?>(null)
        private set

    /** What the update banner says while downloading, or after a failure; null for the default text. */
    var updateStatus by mutableStateOf<String?>(null)
        private set

    var updateBusy by mutableStateOf(false)
        private set

    var updateDismissed by mutableStateOf(false)

    /** Set when the installer has been started: the window must close so it can replace the app. */
    var exitRequested by mutableStateOf(false)
        private set

    private val updates = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "updater").apply { isDaemon = true } }

    /** The habit whose detail screen is open, or null for the list. */
    var openHabitId by mutableStateOf<Long?>(null)

    init {
        synchronized(lock) {
            setToday(computeToday(MIDNIGHT_DELAY_HOURS, 0))
            habitList.reloadAndRecompute()
        }
        scheduler.scheduleAtFixedRate({ refreshToday() }, 1, 1, TimeUnit.MINUTES)
        folder?.let { startSync(it) }
        if (Updater.currentBuild() != null) {
            updates.schedule({ checkForUpdate() }, 10, TimeUnit.SECONDS)
        }
    }

    /** Looks for a newer installer. Being offline or rate-limited is not an error: it tries again later. */
    private fun checkForUpdate(manual: Boolean = false) {
        val current = Updater.currentBuild() ?: return
        try {
            val found = Updater.findUpdate(Updater.fetchReleases(), current)
            if (found != null && (found != update || manual)) {
                update = found
                updateDismissed = false
            }
            if (manual) showUpdateNote(if (found == null) "You have the latest build ($current)." else null)
        } catch (e: Exception) {
            if (manual) showUpdateNote("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
            // Otherwise it tries again at the next check.
        }
    }

    /** The result of the last manual "Updates" click, shown in the top bar for a few seconds. */
    var updateNote by mutableStateOf<String?>(null)
        private set

    /** Checks now instead of waiting for the next scheduled check, and says what it found. */
    fun checkForUpdateNow() {
        if (Updater.currentBuild() == null) {
            showUpdateNote("Running from source (DESKTOP.bat): updates only apply to the installed app.")
            return
        }
        updateNote = "Checking for updates…"
        updates.execute { checkForUpdate(manual = true) }
    }

    private fun showUpdateNote(text: String?) {
        updateNote = text
        if (text != null) updates.schedule({ if (updateNote == text) updateNote = null }, 6, TimeUnit.SECONDS)
    }

    /** Downloads the update, starts its installer, and asks the window to close. */
    fun installUpdate() {
        val info = update ?: return
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Downloading build ${info.build}…"
        updates.execute {
            try {
                val msi = Updater.download(info)
                updateStatus = "Installing… the app will reopen by itself."
                Updater.installAfterExit(msi)
                exitRequested = true
            } catch (e: Exception) {
                updateStatus = "Update failed: ${e.message ?: e.javaClass.simpleName}"
                updateBusy = false
            }
        }
    }

    /** The day changes at 03:00, like the phone's default "midnight delay", not at midnight. */
    private fun refreshToday() {
        val now = computeToday(MIDNIGHT_DELAY_HOURS, 0)
        synchronized(lock) {
            if (now == getToday()) return
            setToday(now)
            habitList.reloadAndRecompute()
        }
        revision++
    }

    fun today(): LocalDate = getToday()

    /**
     * Same matching rule as the phone: name, question or description contains the query.
     * Archived habits are hidden unless [showArchived]; habits that already have an entry today
     * (a check, a number or a skip) are hidden when [showEntered] is false.
     */
    fun rows(days: Int, query: String = "", showArchived: Boolean = false, showEntered: Boolean = true): List<HabitRow> = synchronized(lock) {
        val today = getToday()
        val theme = LightTheme()
        val matcher = HabitMatcher(isArchivedAllowed = showArchived, isEnteredAllowed = showEntered, searchQuery = query)
        habitList.filter { matcher.matches(it) }.map { habit ->
            val c = theme.color(habit.color)
            HabitRow(
                id = habit.id!!,
                name = habit.name,
                category = habit.effectiveCategory,
                type = habit.type,
                color = ((c.red * 255).roundToInt() shl 16) or ((c.green * 255).roundToInt() shl 8) or (c.blue * 255).roundToInt(),
                score = habit.scores[today].value,
                unit = habit.unit,
                cells = (0 until days).map { cellFor(habit, today.minus(it)) },
                archived = habit.isArchived
            )
        }
    }

    private fun cellFor(habit: Habit, date: LocalDate): Cell {
        val entry = habit.computedEntries.get(date)
        if (habit.isTimeOfDay) {
            val minutes = TimeOfDay.minutesOrNull(habit.originalEntries.get(date).value)
            return if (minutes == null) {
                Cell(date, CellKind.UNKNOWN, "?")
            } else {
                Cell(date, CellKind.VALUE, formatTime(minutes))
            }
        }
        if (habit.type == HabitType.NUMERICAL) {
            return if (entry.value == Entry.UNKNOWN) {
                Cell(date, CellKind.UNKNOWN, "?")
            } else {
                Cell(date, CellKind.VALUE, formatNumber(entry.value / 1000.0))
            }
        }
        return when (entry.value) {
            Entry.YES_MANUAL -> Cell(date, CellKind.YES, "✓")
            Entry.YES_AUTO -> Cell(date, CellKind.YES_AUTO, "✓")
            Entry.SKIP -> Cell(date, CellKind.SKIP, "–")
            Entry.NO -> Cell(date, CellKind.NO, "✗")
            else -> Cell(date, CellKind.UNKNOWN, "?")
        }
    }

    /** The entry value shown in the number or time dialog, or null if nothing is entered. */
    fun currentValue(habitId: Long, date: LocalDate): Double? = synchronized(lock) {
        val habit = habitList.getById(habitId) ?: return null
        if (habit.isTimeOfDay) {
            TimeOfDay.minutesOrNull(habit.originalEntries.get(date).value)?.toDouble()
        } else {
            val v = habit.computedEntries.get(date).value
            if (v == Entry.UNKNOWN) null else v / 1000.0
        }
    }

    /** Yes/no habits cycle through the states; the other types need [setNumber] or [setTime]. */
    fun toggle(habitId: Long, date: LocalDate) = edit(habitId) { habit ->
        val current = habit.computedEntries.get(date)
        val next = Entry.nextToggleValue(current.value, isSkipEnabled = true, areQuestionMarksEnabled = true)
        CreateRepetitionCommand(habitList, habit, date, next, current.notes).run()
    }

    fun setNumber(habitId: Long, date: LocalDate, value: Double?) = edit(habitId) { habit ->
        val notes = habit.computedEntries.get(date).notes
        val stored = if (value == null) Entry.UNKNOWN else (value * 1000).roundToInt()
        CreateRepetitionCommand(habitList, habit, date, stored, notes).run()
    }

    fun setTime(habitId: Long, date: LocalDate, minutes: Int?) = edit(habitId) { habit ->
        val notes = habit.computedEntries.get(date).notes
        val stored = if (minutes == null) Entry.UNKNOWN else TimeOfDay.encode(minutes)
        CreateRepetitionCommand(habitList, habit, date, stored, notes).run()
    }

    private fun edit(habitId: Long, block: (Habit) -> Unit) {
        try {
            synchronized(lock) {
                val habit = habitList.getById(habitId) ?: return
                block(habit)
            }
            revision++
            syncSoon()
        } catch (e: Exception) {
            error = "Could not save: ${e.message}"
        }
    }

    // --- detail ---------------------------------------------------------------------------------

    fun detail(habitId: Long, scoreBucket: Int): DetailData? = synchronized(lock) {
        val habit = habitList.getById(habitId) ?: return null
        val theme = LightTheme()
        val c = theme.color(habit.color)
        val overview = OverviewCardPresenter.buildState(habit, theme)
        val scoreState = ScoreCardPresenter.buildState(habit, getFirstWeekdayNumberAccordingToLocale(), scoreBucket, theme)
        val history = HistoryCardPresenter.buildState(habit, getFirstWeekday(), theme)
        val streaks = StreakCartPresenter.buildState(habit, theme)
        DetailData(
            id = habit.id!!,
            name = habit.name,
            category = habit.category,
            question = habit.question,
            type = habit.type,
            color = rgb(c.red, c.green, c.blue),
            targetText = targetText(habit),
            scoreToday = overview.scoreToday.toDouble(),
            monthDiff = overview.scoreMonthDiff.toDouble(),
            yearDiff = overview.scoreYearDiff.toDouble(),
            totalCount = overview.totalCount,
            scores = scoreState.scores.take(60).reversed().map { ScoreBar(it.date, it.value) },
            history = history.series.map {
                when (it) {
                    HistoryChart.Square.ON -> DayKind.ON
                    HistoryChart.Square.DIMMED -> DayKind.DIMMED
                    HistoryChart.Square.GREY -> DayKind.GREY
                    HistoryChart.Square.HATCHED -> DayKind.HATCHED
                    else -> DayKind.OFF
                }
            },
            today = history.today,
            firstWeekday = history.firstWeekday,
            streaks = streaks.bestStreaks.map { StreakInfo(it.start, it.end, it.length) }
        )
    }

    private fun rgb(r: Double, g: Double, b: Double) =
        ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()

    private fun targetText(habit: Habit): String? = when {
        habit.isTimeOfDay && habit.targetValue > 0 ->
            (if (habit.targetType == NumericalHabitType.AT_MOST) "Before " else "After ") + formatTime(habit.targetValue.toInt())
        habit.type == HabitType.NUMERICAL && habit.targetValue > 0 ->
            (if (habit.targetType == NumericalHabitType.AT_MOST) "At most " else "At least ") +
                formatNumber(habit.targetValue) + (if (habit.unit.isNotEmpty()) " ${habit.unit}" else "") + " per day"
        else -> null
    }

    // --- create, edit, archive, delete ------------------------------------------------------------

    fun editorFor(habitId: Long): EditorData? = synchronized(lock) {
        val h = habitList.getById(habitId) ?: return null
        EditorData(
            name = h.name,
            question = h.question,
            description = h.description,
            category = h.category,
            colorIndex = h.color.paletteIndex,
            type = h.type,
            freqNum = h.frequency.numerator,
            freqDen = h.frequency.denominator,
            unit = h.unit,
            targetType = h.targetType,
            targetValue = h.targetValue,
            pin = h.pin
        )
    }

    /** Wake up and bedtime habits can exist only once, archived or not. */
    fun existingTypes(): Set<HabitType> = synchronized(lock) {
        habitList.map { it.type }.filter { it == HabitType.WAKE_UP || it == HabitType.BEDTIME }.toSet()
    }

    fun categories(): List<String> = synchronized(lock) {
        habitList.map { it.category }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** Creates a habit (when [habitId] is null) or edits one. Returns an error message, or null. */
    fun saveHabit(habitId: Long?, d: EditorData): String? {
        val name = d.name.trim().ifEmpty {
            when (d.type) {
                HabitType.WAKE_UP -> "Wake up"
                HabitType.BEDTIME -> "Bedtime"
                else -> ""
            }
        }
        if (name.isEmpty()) return "Give the habit a name."
        if (d.freqNum < 1 || d.freqDen < 1 || d.freqNum > d.freqDen) {
            return "The frequency must be at least once, and no more than once per day."
        }
        try {
            synchronized(lock) {
                val target = factory.buildHabit()
                if (habitId != null) {
                    val existing = habitList.getById(habitId) ?: return "That habit no longer exists."
                    target.copyFrom(existing)
                } else if (d.type in existingTypes()) {
                    return "You already have a ${d.type.name.lowercase().replace('_', ' ')} habit."
                }
                target.name = name
                target.question = d.question.trim()
                target.description = d.description.trim()
                target.category = d.category.trim()
                target.color = PaletteColor(d.colorIndex)
                target.frequency = Frequency(d.freqNum, d.freqDen)
                if (habitId == null) target.type = d.type
                target.unit = d.unit.trim()
                target.targetType = d.targetType
                target.targetValue = d.targetValue
                target.pin = if (target.type == HabitType.WAKE_UP || target.type == HabitType.BEDTIME) d.pin else HabitPin.NONE
                if (habitId == null) {
                    CreateHabitCommand(factory, habitList, target).run()
                } else {
                    EditHabitCommand(habitList, habitId, target).run()
                }
            }
        } catch (e: Exception) {
            return "Could not save: ${e.message}"
        }
        revision++
        syncSoon()
        return null
    }

    fun archive(habitId: Long) = changeList(habitId) { ArchiveHabitsCommand(habitList, listOf(it)).run() }

    fun delete(habitId: Long) = changeList(habitId) { DeleteHabitsCommand(habitList, listOf(it)).run() }

    private fun changeList(habitId: Long, block: (Habit) -> Unit) {
        try {
            synchronized(lock) {
                val habit = habitList.getById(habitId) ?: return
                block(habit)
            }
            if (openHabitId == habitId) openHabitId = null
            revision++
            syncSoon()
        } catch (e: Exception) {
            error = "Could not save: ${e.message}"
        }
    }

    // --- sync ---------------------------------------------------------------------------------

    fun chooseFolder(path: Path) {
        settings.saveFolder(path)
        folder = path
        skippedSync = false
        settings.skippedSync = false
        stopSync()
        startSync(path)
    }

    fun skipSync() {
        settings.skippedSync = true
        skippedSync = true
    }

    private fun startSync(path: Path) {
        try {
            val syncFolder = FileSyncFolder(path)
            val d = DesktopSync(db, syncFolder, lock)
            desktop = d
            syncing = true
            syncStatus = "Syncing…"
            watcher = FolderWatcher(
                d,
                syncFolder.dir,
                onReport = { handleReport(it) },
                onError = { handleError(it) }
            )
        } catch (e: Exception) {
            handleError(e)
        }
    }

    private fun stopSync() {
        pendingSync?.cancel(false)
        watcher?.close()
        watcher = null
        desktop = null
    }

    private fun handleReport(report: SyncReport) {
        if (report.changed) {
            synchronized(lock) { habitList.reloadAndRecompute() }
            revision++
        }
        syncing = false
        val time = java.time.LocalTime.now().withNano(0).toString().take(5)
        syncStatus = if (report.errors.isEmpty()) "Synced at $time" else "Synced at $time, skipped: ${report.errors.joinToString("; ")}"
    }

    private fun handleError(e: Exception) {
        syncing = false
        syncStatus = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Runs a round now, in the background. */
    fun syncNow() {
        val d = desktop ?: return
        syncing = true
        syncStatus = "Syncing…"
        scheduler.execute {
            try {
                handleReport(d.syncOnce())
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Shares a local edit soon, waiting a few seconds so a burst of edits is sent once. */
    private fun syncSoon() {
        val d = desktop ?: return
        pendingSync?.cancel(false)
        pendingSync = scheduler.schedule({
            try {
                handleReport(d.syncOnce())
            } catch (e: Exception) {
                handleError(e)
            }
        }, 3, TimeUnit.SECONDS)
    }

    /** Stops syncing (with a last round) and closes the database. */
    fun close() {
        stopSync()
        scheduler.shutdown()
        updates.shutdown()
        synchronized(lock) { db.close() }
    }

    companion object {
        const val MIDNIGHT_DELAY_HOURS = 3
        fun formatTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        fun formatNumber(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

        /** Reads "7:30", "07:30" or "0730"; returns minutes since midnight, or null if invalid. */
        fun parseTime(text: String): Int? {
            val t = text.trim()
            val parts = if (":" in t) t.split(":") else if (t.length in 3..4) listOf(t.dropLast(2), t.takeLast(2)) else return null
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }
    }
}
