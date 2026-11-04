package org.isoron.uhabits.desktop

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** A newer desktop installer found on GitHub. */
data class UpdateInfo(val build: Int, val url: String)

/**
 * Finds, downloads and starts the installer of a newer version. Releases are published by CI as
 * `mine-N` with a `my-habits-desktop-N.msi` asset; the installed app is version `1.0.N`.
 */
object Updater {
    const val REPO = "AlexMtzRmz0212/uhabits"

    private val ASSET = Regex(
        "\"browser_download_url\"\\s*:\\s*\"(https://[^\"]*/download/[^\"/]*/my-habits-desktop-(\\d+)\\.msi)\""
    )

    /** The build number of the installed app, or null when running from source (no updates then). */
    fun currentBuild(): Int? =
        System.getProperty("jpackage.app-version")?.substringAfterLast('.')?.toIntOrNull()

    /** The installer with the highest build number listed in a GitHub releases response. */
    fun newestIn(json: String): UpdateInfo? =
        ASSET.findAll(json)
            .map { UpdateInfo(it.groupValues[2].toInt(), it.groupValues[1]) }
            .maxByOrNull { it.build }

    fun findUpdate(json: String, current: Int): UpdateInfo? =
        newestIn(json)?.takeIf { it.build > current }

    fun fetchReleases(): String {
        val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MyHabits-Desktop")
        try {
            check(conn.responseCode == 200) { "GitHub answered ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the installer to a temporary folder and returns its path. */
    fun download(info: UpdateInfo): Path {
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "MyHabitsUpdate")
        Files.createDirectories(dir)
        val target = dir.resolve("my-habits-desktop-${info.build}.msi")
        val part = dir.resolve(target.fileName.toString() + ".part")
        val conn = URL(info.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MyHabits-Desktop")
        try {
            check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
            val expected = conn.contentLengthLong
            conn.inputStream.use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
            check(expected < 0 || Files.size(part) == expected) { "Download was cut short" }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            conn.disconnect()
        }
        return target
    }

    /**
     * Starts a small script that waits for this app to close, runs the installer with a progress
     * bar, and opens the new version. The caller must exit the app right after this returns.
     */
    fun installAfterExit(msi: Path) {
        val appPath = System.getProperty("jpackage.app-path")
        val script = msi.resolveSibling("update.cmd")
        val relaunch = if (appPath != null) "if %errorlevel%==0 start \"\" \"$appPath\"" else ""
        Files.writeString(
            script,
            "@echo off\r\n" +
                "timeout /t 3 /nobreak >nul\r\n" +
                "msiexec /i \"$msi\" /passive\r\n" +
                relaunch + "\r\n"
        )
        ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString()).start()
    }
}
