package suwayomi.tachidesk.server.security

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class ActionRateLimiter(
    private val maxAttempts: Int,
    private val windowMs: Long,
) {
    private val attemptsByKey = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun tryAcquire(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val attempts = attemptsByKey.computeIfAbsent(key) { ArrayDeque() }
        synchronized(attempts) {
            while (attempts.isNotEmpty() && (nowMs - attempts.first()) > windowMs) {
                attempts.removeFirst()
            }
            if (attempts.size >= maxAttempts) {
                return false
            }
            attempts.addLast(nowMs)
            return true
        }
    }
}
