package cn.net.rms.chatroom.data.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectBackoffTest {
    private fun capFor(attempt: Int): Long {
        val uncapped = ReconnectBackoff.INITIAL_DELAY_MS * (1L shl minOf(attempt, 5))
        return minOf(uncapped, ReconnectBackoff.MAX_DELAY_MS)
    }

    @Test
    fun `delay stays within half-cap to cap for every attempt`() {
        val rng = Random(42)
        for (attempt in 0..12) {
            val cap = capFor(attempt)
            repeat(100) {
                val delay = ReconnectBackoff.delayMs(attempt, rng)
                assertTrue("attempt=$attempt delay=$delay cap=$cap", delay in (cap / 2)..cap)
            }
        }
    }

    @Test
    fun `delay never exceeds the thirty second cap`() {
        val rng = Random(7)
        for (attempt in 5..40) {
            repeat(50) {
                assertTrue(ReconnectBackoff.delayMs(attempt, rng) <= ReconnectBackoff.MAX_DELAY_MS)
            }
        }
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, capFor(100))
    }

    @Test
    fun `delay range grows with attempts up to the cap`() {
        // Doubles while uncapped (1s→2s→4s→8s→16s); from attempt 5 on the 30s
        // cap flattens the sequence.
        for (attempt in 0..3) {
            assertTrue(
                "attempt=$attempt",
                capFor(attempt + 1) >= capFor(attempt) * 2
            )
        }
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, capFor(5))
        assertEquals(capFor(5), capFor(6))
    }

    @Test
    fun `jitter spreads delays instead of retrying in lockstep`() {
        val rng = Random(1)
        val delays = (1..200).map { ReconnectBackoff.delayMs(5, rng) }.toSet()
        assertTrue("expected jittered variety, got ${delays.size} distinct values", delays.size > 10)
    }
}
