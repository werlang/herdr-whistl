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
    /**
     * The space this agent belongs to, from its space label; `null` when it carries none.
     *
     * Two labels mean the same thing: this app writes [SPACE_LABEL], and the Herdr desktop app writes [HERDR_SPACE_LABEL]
     * for the terminals it starts itself. A terminal of either kind belongs to a space, and one started on the desktop has
     * to be visible here too (0.7.1).
     */
    val spaceId: String?
        get() = terminal.labels[SPACE_LABEL] ?: terminal.labels[HERDR_SPACE_LABEL]

    /**
     * The name to show: the one the user gave it, else the one the core has, else the program's name, else the address.
     *
     * The core cannot rename a live terminal, so a rename is a label ([NAME_LABEL]) that wins over the name it was
     * started with (phase 9, SPEC §2.4).
     */
    val displayName: String
        get() = terminal.labels[NAME_LABEL]?.trim()?.takeIf { it.isNotEmpty() }
            ?: terminal.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: terminal.argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: terminal.address

    companion object {
        /** The hc label that ties a terminal to a space. */
        const val SPACE_LABEL: String = "whistl.space"

        /** The space label the Herdr desktop app writes on the terminals it starts; a terminal of either kind is claimed. */
        const val HERDR_SPACE_LABEL: String = "herdr.space"

        /**
         * The tab label the Herdr desktop app writes on the terminals it starts in a tab. The desktop's document maps the tab
         * id to the space that owns it, so a terminal whose `herdr.space` is unknown can still resolve to its space by tab.
         */
        const val HERDR_TAB_LABEL: String = "herdr.tab"

        /** The hc label that carries a name the user chose. */
        const val NAME_LABEL: String = "whistl.name"
    }
}
