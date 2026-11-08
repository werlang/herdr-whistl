package io.github.werlang.whistl.core.log

/**
 * The debug build's log output: `android.util.Log`. The release source set has an object with the same name whose
 * methods do nothing, so this class and its Android call are never in a release dex.
 */
internal object LogOutput {
    /**
     * Writes a debug message.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    fun debug(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    /**
     * Writes a warning.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    fun warning(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }
}
