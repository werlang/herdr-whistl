package io.github.werlang.whistl.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The retry schedule: 1 s, 2 s, 4 s, 8 s, 16 s, 32 s, then 60 s, and it never goes past 60 s. */
class BackoffTest {

    @Test
    fun scheduleDoublesFromOneSecondAndCapsAtSixtySeconds() {
        val delays = (0..8).map { Backoff.delayMillis(it) }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
    }

    /** Large attempt counts stay at the cap and do not overflow. */
    @Test
    fun hugeAttemptCountsStayAtTheCap() {
        assertEquals(60_000L, Backoff.delayMillis(1_000))
        assertEquals(60_000L, Backoff.delayMillis(Int.MAX_VALUE))
    }
}
