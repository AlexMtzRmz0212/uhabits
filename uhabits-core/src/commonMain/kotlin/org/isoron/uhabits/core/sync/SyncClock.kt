package org.isoron.uhabits.core.sync

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Hybrid logical clock. Stamps have the form `<wallMillis>-<counter>-<deviceId>`, with the first
 * two fields zero-padded, so comparing two stamps as strings orders them by time, then by counter,
 * then by device. A stamp handed out is always greater than any stamp seen before, even if the
 * wall clock moves backwards or another device's clock runs ahead.
 */
class SyncClock(
    val deviceId: String,
    private val nowMillis: () -> Long = defaultNowMillis,
    initial: String? = null
) {
    private var lastWall = 0L
    private var lastCounter = 0

    init {
        initial?.let { observe(it) }
    }

    fun next(): String {
        val now = nowMillis()
        if (now > lastWall) {
            lastWall = now
            lastCounter = 0
        } else {
            lastCounter++
        }
        return format(lastWall, lastCounter, deviceId)
    }

    /** Advances this clock past [stamp], so the next stamp issued here sorts after it. */
    fun observe(stamp: String) {
        val parsed = parse(stamp) ?: return
        if (parsed.first > lastWall || (parsed.first == lastWall && parsed.second > lastCounter)) {
            lastWall = parsed.first
            lastCounter = parsed.second
        }
    }

    /** The most recent stamp issued or observed, or null if none. */
    fun last(): String? = if (lastWall == 0L) null else format(lastWall, lastCounter, deviceId)

    companion object {
        @OptIn(ExperimentalTime::class)
        val defaultNowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }

        fun format(wall: Long, counter: Int, deviceId: String): String =
            wall.toString().padStart(13, '0') + "-" + counter.toString().padStart(5, '0') + "-" + deviceId

        /** Returns (wallMillis, counter), or null if [stamp] is malformed. */
        fun parse(stamp: String): Pair<Long, Int>? {
            val parts = stamp.split("-", limit = 3)
            if (parts.size != 3) return null
            val wall = parts[0].toLongOrNull() ?: return null
            val counter = parts[1].toIntOrNull() ?: return null
            return Pair(wall, counter)
        }
    }
}
