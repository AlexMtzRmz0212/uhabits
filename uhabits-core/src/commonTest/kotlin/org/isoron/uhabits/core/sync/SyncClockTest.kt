package org.isoron.uhabits.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncClockTest {
    @Test
    fun testStampsIncreaseWhenWallClockStandsStill() {
        val clock = SyncClock("aaa", nowMillis = { 5000 })
        val a = clock.next()
        val b = clock.next()
        val c = clock.next()
        assertTrue(a < b)
        assertTrue(b < c)
    }

    @Test
    fun testStampsIncreaseWhenWallClockGoesBackwards() {
        var now = 5000L
        val clock = SyncClock("aaa", nowMillis = { now })
        val a = clock.next()
        now = 1000
        val b = clock.next()
        assertTrue(a < b)
    }

    @Test
    fun testObserveMovesClockPastRemoteStamp() {
        val local = SyncClock("aaa", nowMillis = { 1000 })
        val remote = SyncClock("bbb", nowMillis = { 9000 })
        val remoteStamp = remote.next()
        local.observe(remoteStamp)
        assertTrue(local.next() > remoteStamp)
    }

    @Test
    fun testInitialStampKeepsClockMonotonicAcrossRestarts() {
        val first = SyncClock("aaa", nowMillis = { 8000 })
        val last = first.next()
        val restarted = SyncClock("aaa", nowMillis = { 2000 }, initial = last)
        assertTrue(restarted.next() > last)
    }

    @Test
    fun testStampsFromDifferentDevicesNeverTie() {
        val a = SyncClock("aaa", nowMillis = { 5000 }).next()
        val b = SyncClock("bbb", nowMillis = { 5000 }).next()
        assertTrue(a != b)
    }

    @Test
    fun testParseAndFormat() {
        assertEquals(Pair(1234L, 7), SyncClock.parse(SyncClock.format(1234, 7, "dev-1")))
        assertNull(SyncClock.parse("garbage"))
        assertNull(SyncClock.parse("abc-def-ghi"))
    }

    @Test
    fun testLastIsNullBeforeFirstStamp() {
        assertNull(SyncClock("aaa", nowMillis = { 5000 }).last())
    }
}
