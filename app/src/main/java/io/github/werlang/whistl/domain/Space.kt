package io.github.werlang.whistl.domain

/**
 * A named folder on one machine where agents work (SPEC §1). Pure app abstraction.
 *
 * Lives in that machine's core as an entry of the `whistl` document (SPEC §5).
 *
 * @property id the space id, `sp_` followed by 16 hex characters.
 * @property name display name.
 * @property folder the folder on the machine, for example `~/code/web-app`.
 * @property pinned whether the space is pinned to the top of its machine group.
 * @property order the user's order within the machine.
 * @property machineId the id of the [Machine] that holds the folder.
 */
data class Space(
    val id: String,
    val name: String,
    val folder: String,
    val pinned: Boolean,
    val order: Int,
    val machineId: String,
)
