package io.github.werlang.whistl.core.log

/**
 * The app's logger (SPEC §6). A debug build writes to `android.util.Log`; a release build writes nothing, because the
 * release source set supplies a [LogOutput] whose methods do nothing and never refer to `android.util.Log`.
 *
 * The API takes a tag and a message and nothing else: no `Throwable`, and no formatting arguments, so user data is not
 * spliced in by accident. Callers pass fixed phrases and counts.
 *
 * Never pass terminal text, host names, user names, folder paths or credentials to this logger, in any build.
 */
object Log {
    /**
     * A debug-level message. Release builds drop it.
     *
     * @param tag a short name for the source, for example `SpacesRepository`.
     * @param message a fixed phrase or a count. Never terminal text, hosts or credentials.
     */
    fun d(tag: String, message: String) {
        LogOutput.debug(tag, message)
    }

    /**
     * A warning. Release builds drop it.
     *
     * @param tag a short name for the source.
     * @param message a fixed phrase or a count. Never terminal text, hosts or credentials.
     */
    fun w(tag: String, message: String) {
        LogOutput.warning(tag, message)
    }
}
