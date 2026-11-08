package io.github.werlang.whistl.core.log

/**
 * Where [Log] messages go. The debug source set's [LogOutput] writes to `android.util.Log`; the release source set's writes
 * nothing. Unit tests replace the sink with a recording one, so a test that reaches a warning neither throws "not mocked" nor
 * depends on the Android stub (phase 7 r6 step 0; the global `isReturnDefaultValues` flag is not used).
 */
interface LogSink {
    /**
     * A debug-level message.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    fun debug(tag: String, message: String)

    /**
     * A warning.
     *
     * @param tag the source's tag.
     * @param message the message.
     */
    fun warning(tag: String, message: String)
}

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
     * The sink every message goes to: the build type's [LogOutput] unless a test replaces it. Replace it only for the duration
     * of a test, and put the default back afterwards.
     */
    @Volatile
    var sink: LogSink = LogOutput

    /**
     * A debug-level message. Release builds drop it.
     *
     * @param tag a short name for the source, for example `SpacesRepository`.
     * @param message a fixed phrase or a count. Never terminal text, hosts or credentials.
     */
    fun d(tag: String, message: String) {
        sink.debug(tag, message)
    }

    /**
     * A warning. Release builds drop it.
     *
     * @param tag a short name for the source.
     * @param message a fixed phrase or a count. Never terminal text, hosts or credentials.
     */
    fun w(tag: String, message: String) {
        sink.warning(tag, message)
    }
}
