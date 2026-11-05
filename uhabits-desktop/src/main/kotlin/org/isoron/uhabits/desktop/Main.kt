package org.isoron.uhabits.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.isoron.platform.io.getVersion
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.sync.SyncRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """My Habits (desktop shell)

Usage: myhabits [command] [--db <file>] [--folder <dir>]

With no command, the window opens.

Commands:
  init <folder>   Remember the sync folder, create the database and run the first sync
  sync            Run one sync round
  watch           Sync now, then keep syncing until you press Ctrl+C
  list            Print the habits in the database
  status          Show the database, version, device id and sync folder

--db <file>       Use this database instead of %APPDATA%\MyHabits\uhabits.db
--folder <dir>    Use this sync folder instead of the remembered one
"""

fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    var dbArg: String? = null
    var folderArg: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--db" -> dbArg = args.getOrNull(++i)
            "--folder" -> folderArg = args.getOrNull(++i)
            else -> positional.add(args[i])
        }
        i++
    }
    val command = positional.firstOrNull()
    if (command in listOf("help", "-h", "--help")) {
        print(USAGE)
        return
    }

    val dbPath = dbArg?.let { Paths.get(it) } ?: DesktopDatabase.defaultPath()
    val settings = Settings(dbPath)

    if (command == null) {
        openWindow(dbPath)
        return
    }

    try {
        when (command) {
            "init" -> {
                val folder = Paths.get(positional.getOrNull(1) ?: fail("init needs a folder"))
                settings.saveFolder(folder)
                runSync(dbPath, folder)
            }
            "sync" -> runSync(dbPath, requireFolder(folderArg, settings))
            "watch" -> runWatch(dbPath, requireFolder(folderArg, settings))
            "list" -> listHabits(dbPath)
            "status" -> showStatus(dbPath, folderArg?.let { Paths.get(it) } ?: settings.savedFolder())
            else -> fail("unknown command: $command")
        }
    } catch (e: UnsupportedDatabaseException) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message\n")
    print(USAGE)
    exitProcess(2)
}

private fun requireFolder(arg: String?, settings: Settings): Path =
    arg?.let { Paths.get(it) } ?: settings.savedFolder() ?: fail("no sync folder yet, run: myhabits init <folder>")

private fun describe(report: SyncReport): String {
    val parts = mutableListOf<String>()
    parts.add(if (report.changed) "applied ${report.applied} changes from ${report.peersMerged} device(s)" else "up to date")
    if (report.wroteOwnFile) parts.add("wrote this device's file")
    report.errors.forEach { parts.add("skipped $it") }
    return parts.joinToString(", ")
}

private fun runSync(dbPath: Path, folder: Path) {
    val db = DesktopDatabase.open(dbPath)
    try {
        val desktop = DesktopSync(db, FileSyncFolder(folder))
        println("Sync: " + describe(desktop.syncOnce()))
    } finally {
        db.close()
    }
}

private fun runWatch(dbPath: Path, folder: Path) {
    val db = DesktopDatabase.open(dbPath)
    val syncFolder = FileSyncFolder(folder)
    val desktop = DesktopSync(db, syncFolder)
    val watcher = FolderWatcher(
        desktop,
        syncFolder.dir,
        onReport = { println("Sync: " + describe(it)) },
        onError = { System.err.println("Sync failed: ${it.message}") }
    )
    println("Watching ${syncFolder.dir}. Press Ctrl+C to stop.")
    Runtime.getRuntime().addShutdownHook(
        Thread {
            watcher.close()
            db.close()
        }
    )
    Thread.currentThread().join()
}

private fun listHabits(dbPath: Path) {
    val db = DesktopDatabase.open(dbPath)
    try {
        val habits = HabitRepository(db).findAll()
        if (habits.isEmpty()) println("No habits yet.")
        for (h in habits) {
            val tag = if (h.archived != 0) " (archived)" else ""
            val category = if (h.category.isNotEmpty()) "[${h.category}] " else ""
            println("${h.position + 1}. $category${h.name}$tag")
        }
    } finally {
        db.close()
    }
}

private fun showStatus(dbPath: Path, folder: Path?) {
    println("App version: ${System.getProperty("jpackage.app-version") ?: "(running from source)"}")
    println("Database:    $dbPath")
    if (!Files.exists(dbPath)) {
        println("             (not created yet)")
    } else {
        val db = DesktopDatabase.open(dbPath)
        try {
            println("Version:     ${db.getVersion()}")
            println("Device id:   ${SyncRepository(db).deviceId}")
        } finally {
            db.close()
        }
    }
    println("Sync folder: ${folder ?: "(none, run: myhabits init <folder>)"}")
}

private const val ZOOM_STEP = 0.1f

@OptIn(ExperimentalComposeUiApi::class)
private fun openWindow(dbPath: Path) {
    val model = try {
        AppModel(dbPath)
    } catch (e: UnsupportedDatabaseException) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
    application {
        // After an update is downloaded and its installer started, close so the installer can replace the app.
        LaunchedEffect(model.exitRequested) {
            if (model.exitRequested) {
                model.close()
                exitApplication()
            }
        }
        Window(
            onCloseRequest = {
                model.close()
                exitApplication()
            },
            title = "My Habits",
            icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) },
            state = rememberWindowState(width = 1000.dp, height = 700.dp),
            // Ctrl + / Ctrl - / Ctrl 0, like a browser.
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                    when (event.key) {
                        Key.Equals, Key.Plus, Key.NumPadAdd -> { model.zoomBy(ZOOM_STEP); true }
                        Key.Minus, Key.NumPadSubtract -> { model.zoomBy(-ZOOM_STEP); true }
                        Key.Zero, Key.NumPad0 -> { model.resetZoom(); true }
                        else -> false
                    }
                } else {
                    false
                }
            }
        ) {
            // Scaling the density scales every dp and sp, so the whole window zooms together.
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * model.zoom, base.fontScale)) {
                Box(
                    modifier = Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                        if (event.keyboardModifiers.isCtrlPressed) {
                            val change = event.changes.first()
                            // Wheel up zooms in, wheel down zooms out.
                            if (change.scrollDelta.y != 0f) model.zoomBy(if (change.scrollDelta.y < 0) ZOOM_STEP else -ZOOM_STEP)
                            event.changes.forEach { it.consume() }
                        }
                    }
                ) {
                    App(model)
                }
            }
        }
    }
}
