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
     * The name to show: the one the user gave it in Whistl, else the Herdr desktop's tab, else the core's own name, else the
     * program's name, else the address. The screens with the desktop's tab map call [terminalDisplayName] with the tab name;
     * this property is the same rule without that context.
     *
     * The core cannot rename a live terminal, so a rename is a label ([NAME_LABEL]) that wins over every name it was started
     * with (phase 9, SPEC §2.4).
     */
    val displayName: String
        get() = terminalDisplayName(terminal)

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

        /**
         * The label that carries the Herdr desktop's `space / tab` text on paths that put it under `labels` (`hc` 0.1.19).
         * The same text may also arrive as the terminal's top-level `where`.
         */
        const val HERDR_WHERE_LABEL: String = "where"

        /** The hc label that carries a name the user chose. */
        const val NAME_LABEL: String = "whistl.name"
    }
}

/**
 * The name every surface shows for a terminal (SPEC §1, §2): the Whistl rename, the Herdr desktop's tab, its `where` text,
 * the core's explicit name, the core's agent identity, the program's file name, then the address.
 *
 * The tab is matched by exact id (the desktop's document), never by splitting its name, so a tab named `Fix / Deploy` keeps
 * its whole name. `where` is `space / tab`; with the space's name in hand the exact prefix is removed, which also preserves
 * ` / ` inside the tab. Without that context the last ` / ` segment is used, which is documented in SPEC §1. The dynamic
 * `title` is deliberately not used: it is the program's running title, not the terminal's name.
 *
 * @param terminal the terminal to name.
 * @param tabName the name of the desktop tab whose id the terminal carries, or `null` when there is none.
 * @param spaceName the name of the space the terminal matched, for removing the exact prefix of `where`.
 * @return the name to show.
 */
fun terminalDisplayName(
    terminal: Terminal,
    tabName: String? = null,
    spaceName: String? = null,
): String = terminal.labels[Agent.NAME_LABEL]?.trim()?.takeIf { it.isNotEmpty() }
    ?: tabName?.trim()?.takeIf { it.isNotEmpty() }
    ?: whereName(terminal.where, spaceName)
    ?: whereName(terminal.labels[Agent.HERDR_WHERE_LABEL], spaceName)
    ?: terminal.name?.trim()?.takeIf { it.isNotEmpty() }
    ?: terminal.agentApp?.trim()?.takeIf { it.isNotEmpty() }
    ?: terminal.argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    ?: terminal.address

/**
 * The terminal's name out of the desktop's `where` text.
 *
 * @param where the `space / tab` text, or `null`.
 * @param spaceName the matched space's name, or `null` when there is no context.
 * @return the tab name, or `null` when [where] is blank.
 */
private fun whereName(where: String?, spaceName: String?): String? {
    val text = where?.trimStart()?.takeIf { it.isNotBlank() } ?: return null
    val space = spaceName?.trim()?.takeIf { it.isNotEmpty() }
    if (space != null && text.startsWith("$space / ")) {
        return text.removePrefix("$space / ").trim().takeIf { it.isNotEmpty() }
    }
    val separator = text.lastIndexOf(" / ")
    if (separator < 0) return text.trim().takeIf { it.isNotEmpty() }
    // Without the matched space's exact name there is no way to tell a " / " inside the tab from the separator. The last
    // segment is the documented fallback; the exact tab id above is what avoids the ambiguity when the document has it.
    return text.substring(separator + 3).trim().takeIf { it.isNotEmpty() }
}
