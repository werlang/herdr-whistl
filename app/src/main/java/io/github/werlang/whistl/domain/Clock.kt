package io.github.werlang.whistl.domain

/**
 * A source of time in milliseconds, injected wherever the app measures an interval (SPEC §5 throttles).
 *
 * Production code gets its clock from the composition root. Tests back it with the virtual scheduler
 * (`testScheduler.currentTime`), so no wall clock is read anywhere in the data layer.
 */
fun interface Clock {
    /**
     * The current time.
     *
     * @return milliseconds on a monotonic timeline of the caller's choice.
     */
    fun nowMillis(): Long
}
