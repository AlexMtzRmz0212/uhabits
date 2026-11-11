package org.isoron.uhabits.core.sync

/**
 * Turns a [SyncReport] into the sentence both apps show, so the phone and the PC say the same
 * things: whether this device's changes were sent, and how fresh the other device's file is.
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
     * One line, for example "sent your changes · other device's file from 14:20 (12 min ago), got 2
     * changes". [clock] formats an epoch-millisecond time as a short clock time.
     */
    fun describe(report: SyncReport, now: Long, clock: (Long) -> String): String {
        val parts = mutableListOf<String>()
        parts.add(if (report.wroteOwnFile) "sent your changes" else "your file already current")

        val modified = report.peerModified
        parts.add(
            when {
                report.peersSeen == 0 -> "no other device's file found yet"
                modified == null -> "other device's file found, " + got(report)
                else -> "other device's file from ${clock(modified)} (${age(now - modified)}), " + got(report)
            }
        )
        if (report.unreadable > 0) parts.add("${report.unreadable} file(s) unreadable, probably still uploading")
        report.errors.forEach { parts.add("skipped $it") }
        return parts.joinToString(" · ")
    }

    private fun got(report: SyncReport) =
        if (report.applied > 0) "got ${report.applied} changes" else "nothing new"
}
