package io.github.werlang.whistl.core.log

/**
 * The debug build's log output: `android.util.Log`. The release source set has an object with the same name whose
 * methods do nothing, so this class and its Android call are never in a release dex.
 */
internal object LogOutput : LogSink {
    /**
     * Writes a debug message.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    override fun debug(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    /**
     * Writes a warning.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    override fun warning(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }
}
