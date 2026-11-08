package io.github.werlang.whistl.data

/**
 * The retry schedule after a lost connection (SPEC §2, round p3r1): 1 s, 2 s, 4 s, 8 s, 16 s, 32 s, then 60 s for good.
 *
 * The attempt counter resets to zero when a connection succeeds, and when the last lease is released.
 */
object Backoff {
    /** The first delay: 1 second. */
    const val FIRST_DELAY_MILLIS: Long = 1_000L

    /** The longest delay between two attempts: 60 seconds. */
    const val CAP_MILLIS: Long = 60_000L

    /**
     * The delay before retry number [attempt], counted from zero.
     *
     * @param attempt how many retries have already been scheduled since the last success.
     * @return the delay in milliseconds, never more than [CAP_MILLIS].
     */
    fun delayMillis(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        return minOf(FIRST_DELAY_MILLIS shl shift, CAP_MILLIS)
    }

    /** Beyond this shift every delay is already at the cap, so the shift is clamped to stay in range. */
    private const val MAX_SHIFT: Int = 16
}
