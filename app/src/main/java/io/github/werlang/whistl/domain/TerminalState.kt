package io.github.werlang.whistl.domain

/**
 * Lifecycle state of a terminal on a core (SPEC §5: `state` is `live` or `finished`).
 *
 * @property wire the exact string hc sends for this state.
 */
enum class TerminalState(val wire: String) {
    /** The process is running. */
    Live("live"),

    /** The process has exited; the terminal is kept until removed. */
    Finished("finished");

    companion object {
        /**
         * Maps a wire state to [TerminalState].
         *
         * Choice for unknown values: `null`, so the caller sees "unknown" and does not guess. Parsing never crashes.
         *
         * @param value the state string from hc.
         * @return the matching state, or `null` when the value is unknown.
         */
        fun fromWire(value: String?): TerminalState? = entries.firstOrNull { it.wire == value }
    }
}
