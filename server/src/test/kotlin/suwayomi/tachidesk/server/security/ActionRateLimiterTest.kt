package suwayomi.tachidesk.server.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionRateLimiterTest {
    @Test
    fun `tryAcquire allows up to threshold within window and then blocks`() {
        val limiter = ActionRateLimiter(maxAttempts = 3, windowMs = 1_000)

        assertTrue(limiter.tryAcquire("key-a", nowMs = 1_000))
        assertTrue(limiter.tryAcquire("key-a", nowMs = 1_100))
        assertTrue(limiter.tryAcquire("key-a", nowMs = 1_200))
        assertFalse(limiter.tryAcquire("key-a", nowMs = 1_300))
    }

    @Test
    fun `tryAcquire keeps attempt at exact boundary and resets one millisecond after window`() {
        val limiter = ActionRateLimiter(maxAttempts = 1, windowMs = 1_000)

        assertTrue(limiter.tryAcquire("key-b", nowMs = 10_000))
        assertFalse(
            limiter.tryAcquire("key-b", nowMs = 11_000),
            "Attempt exactly at window boundary should still be limited",
        )
        assertTrue(
            limiter.tryAcquire("key-b", nowMs = 11_001),
            "Attempt should be allowed once outside the window",
        )
    }
}
