package io.github.werlang.whistl.domain

/**
 * A machine as hc sees it: a target the core can reach (the `machines` entries of `terminal.list` and `machine.list`).
 *
 * Not the same as [Machine], which is the app's own connection settings (SPEC §1).
 *
 * @property id hc's machine id.
 * @property name the name hc shows for the machine.
 * @property host the host name.
 * @property target the target string, for example `user@host`.
 * @property state hc's connection state, for example `connected`.
 * @property version the hc version on that machine, if reported.
 * @property session session name, if any.
 * @property temporary whether the machine entry is temporary.
 * @property via whether the machine is reached through another machine.
 */
data class CoreMachine(
    val id: String,
    val name: String,
    val host: String,
    val target: String,
    val state: String,
    val version: String?,
    val session: String?,
    val temporary: Boolean,
    val via: Boolean,
)
