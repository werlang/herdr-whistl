package io.github.werlang.whistl.domain

/**
 * An agent: a terminal on a core, started in a space's folder (SPEC §1).
 *
 * Membership is the terminal label `whistl.space=<space id>`. Agents started elsewhere have no such
 * label and are matched by folder later (phase 3).
 *
 * @property terminal the terminal this agent runs in.
 */
data class Agent(val terminal: Terminal) {
    /** The space this agent belongs to, from its `whistl.space` label; `null` when unlabelled. */
    val spaceId: String?
        get() = terminal.labels[SPACE_LABEL]

    companion object {
        /** The hc label that ties a terminal to a space. */
        const val SPACE_LABEL: String = "whistl.space"
    }
}
