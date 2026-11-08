package io.github.werlang.whistl.domain

/**
 * Agent status as hc reports it (SPEC §5).
 *
 * A plain program has no status: hc sends `null`, which the app models as `AgentStatus?` = null.
 *
 * @property wire the exact string hc sends for this status.
 */
enum class AgentStatus(val wire: String) {
    /** Waiting for input. */
    Idle("idle"),

    /** Busy working on a task. */
    Working("working"),

    /** Finished its last task. */
    Done("done"),

    /** Waiting for the user; counts toward the "waiting" badge. */
    Blocked("blocked"),

    /** Reported an error. */
    Error("error");

    companion object {
        /**
         * Maps a wire status to [AgentStatus].
         *
         * Choice for unknown values: a value this client does not know maps to `null`, the same as a
         * plain program. The app never guesses a status, and an unknown value never crashes parsing.
         *
         * @param value the status string from hc, or `null` for a plain program.
         * @return the matching status, or `null` for `null` and for unknown values.
         */
        fun fromWire(value: String?): AgentStatus? = entries.firstOrNull { it.wire == value }
    }
}
