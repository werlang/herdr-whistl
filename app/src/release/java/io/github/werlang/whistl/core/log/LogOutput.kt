package io.github.werlang.whistl.core.log

/**
 * The release build's log output: nothing. It never refers to `android.util.Log`, so a release build writes no log
 * lines (SPEC §6). The debug source set has an object with the same name that does write.
 */
internal object LogOutput {
    /**
     * Drops a debug message.
     *
     * @param tag the source's tag, unused.
     * @param message the message, unused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun debug(tag: String, message: String) = Unit

    /**
     * Drops a warning.
     *
     * @param tag the source's tag, unused.
     * @param message the message, unused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun warning(tag: String, message: String) = Unit
}
