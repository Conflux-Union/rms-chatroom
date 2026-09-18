package cn.net.rms.chatroom.data.websocket

import kotlin.random.Random

/**
 * Pure reconnect-backoff math shared by the WebSocket connections.
 * Exponential growth (1s, 2s, 4s ... capped at 30s) with equal jitter: each
 * retry waits a uniform random time in [cap/2, cap], so the three sockets
 * restarting after a network flap do not retry in lockstep.
 */
object ReconnectBackoff {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L
    const val MAX_ATTEMPTS = 10

    /** Delay before retry number [attempt] (0-based). Always in [cap/2, cap]. */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val uncapped = INITIAL_DELAY_MS * (1L shl minOf(attempt, 5))
        val cap = minOf(uncapped, MAX_DELAY_MS)
        return cap / 2 + random.nextLong(cap / 2 + 1)
    }
}
