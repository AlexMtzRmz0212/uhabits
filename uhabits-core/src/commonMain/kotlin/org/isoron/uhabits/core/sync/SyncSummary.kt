package org.isoron.uhabits.core.sync

/**
 * Turns a [SyncReport] into the short sentences both apps show, one for the upload half and one
 * for the download half, so each says only what its own process did.
 */
object SyncSummary {
    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun age(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /**
     * What the upload did, for example "Sent your changes · file in the folder is from 14:32".
     * [clock] formats an epoch-millisecond time as a short clock time.
     */
    fun describeUpload(report: SyncReport, now: Long, clock: (Long) -> String): String {
        val modified = report.ownModified
        val file = if (modified == null) "" else "file in the folder is from ${clock(modified)} (${age(now - modified)})"
        return when {
            report.wroteOwnFile && report.ownVerified == false ->
                "Wrote your file but could not read it back: check the sync folder"
            report.wroteOwnFile ->
                listOf("Sent your changes", file, "Drive uploads it to the cloud next").filter { it.isNotEmpty() }.joinToString(" · ")
            else ->
                listOf("Nothing new to send", file).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /**
     * What the download found, for example "Other device's file from 14:20 (12 min ago) · got 2
     * changes".
     */
    fun describeDownload(report: SyncReport, now: Long, clock: (Long) -> String): String {
        if (report.peersSeen == 0) return "No other device's file in the folder yet: Drive has not delivered it"
        val modified = report.peerModified
        val file = if (modified == null) "Other device's file found" else "Other device's file from ${clock(modified)} (${age(now - modified)})"
        val parts = mutableListOf(file, if (report.applied > 0) "got ${report.applied} changes" else "nothing new")
        if (report.unreadable > 0) parts.add("${report.unreadable} file(s) unreadable, probably still uploading")
        report.errors.forEach { parts.add("skipped $it") }
        return parts.joinToString(" · ")
    }

    /** Both halves on two lines, for a full round. */
    fun describe(report: SyncReport, now: Long, clock: (Long) -> String): String =
        "Upload: " + describeUpload(report, now, clock) + "\n" + "Download: " + describeDownload(report, now, clock)
}
