package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.Terminal

/**
 * Pure rules for spaces (SPEC §1, §5): matching an agent to its space, the order spaces are shown in, and the edits to
 * the `whistl` document. No I/O, no clock, no Android.
 */

/**
 * Removes a trailing `/` from a folder path before comparing, so `/home/u/web/` and `/home/u/web` are the same folder.
 * A lone `/` stays `/`. No `~` expansion happens in this phase: `~` compares as written.
 *
 * @param path the folder path as stored or as the core reports it.
 * @return the path with any trailing slash removed.
 */
fun normalizeFolder(path: String): String = if (path.length > 1) path.trimEnd('/').ifEmpty { "/" } else path

/**
 * The order spaces are shown in (SPEC §1): pinned spaces first, then by `order`. Spaces that tie keep their document
 * order, because the sort is stable. No other ordering is applied.
 *
 * @param spaces the spaces in document order.
 * @return the same spaces, sorted for display.
 */
fun sortSpaces(spaces: List<SpaceRecord>): List<SpaceRecord> =
    spaces.sortedWith(compareBy<SpaceRecord>({ !it.pinned }, { it.order }))

/**
 * The space an agent belongs to (SPEC §1, precedence in this order):
 * 1. a `whistl.space` label that names a space on this machine;
 * 2. otherwise the first space whose folder equals the terminal's `cwd`, after [normalizeFolder];
 * 3. otherwise `null`, shown as "No space".
 *
 * A label naming a space that does not exist falls through to the cwd rule.
 *
 * @param terminal the agent's terminal.
 * @param spaces the machine's spaces, in display order.
 * @return the matched space, or `null`.
 */
fun matchSpace(terminal: Terminal, spaces: List<SpaceRecord>): SpaceRecord? {
    val label = terminal.labels[Agent.SPACE_LABEL]
    if (label != null) {
        spaces.firstOrNull { it.id == label }?.let { return it }
    }
    val cwd = terminal.cwd?.let(::normalizeFolder) ?: return null
    return spaces.firstOrNull { normalizeFolder(it.folder) == cwd }
}

/**
 * The name a new space gets when none is typed: the last segment of its folder, so `~/code/web-app` is `web-app`.
 *
 * @param folder the space's folder.
 * @return the last path segment, or the folder itself when there is none.
 */
fun defaultSpaceName(folder: String): String {
    val last = normalizeFolder(folder).substringAfterLast('/')
    return last.ifEmpty { folder }
}

/** Makes ids for new spaces (SPEC §5): `sp_` followed by 16 lowercase hex characters. */
fun interface SpaceIdSource {
    /**
     * A new id.
     *
     * @return the id, for example `sp_0123456789abcdef`.
     */
    fun next(): String
}

/**
 * Space ids from 8 random bytes. The byte source is injected: production passes a secure random, tests a seeded one.
 *
 * @param fill fills the array it is given with random bytes.
 * @return the id source.
 */
fun spaceIdsFrom(fill: (ByteArray) -> Unit): SpaceIdSource = SpaceIdSource {
    val bytes = ByteArray(8)
    fill(bytes)
    "sp_" + bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * Adds a space at the end of the document.
 *
 * @param space the space to add.
 * @return the document with the space appended.
 */
fun SpacesDocument.withSpace(space: SpaceRecord): SpacesDocument = copy(spaces = spaces + space)

/**
 * The `order` for a space added at the end: one more than the highest existing order.
 *
 * @return the next order value.
 */
fun SpacesDocument.nextOrder(): Int = (spaces.maxOfOrNull { it.order } ?: -1) + 1

/**
 * Whether the document holds a space with this id.
 *
 * @param id the space id.
 * @return `true` when it does.
 */
fun SpacesDocument.hasSpace(id: String): Boolean = spaces.any { it.id == id }

/**
 * Applies [transform] to the space with [id]. Other spaces, and their unknown fields, are untouched.
 *
 * @param id the space id.
 * @param transform the change to the one space.
 * @return the document with that space changed; unchanged when no space has [id].
 */
fun SpacesDocument.mapSpace(id: String, transform: (SpaceRecord) -> SpaceRecord): SpacesDocument =
    copy(spaces = spaces.map { if (it.id == id) transform(it) else it })

/**
 * Removes the space with [id].
 *
 * @param id the space id.
 * @return the document without that space.
 */
fun SpacesDocument.without(id: String): SpacesDocument = copy(spaces = spaces.filterNot { it.id == id })

/**
 * Moves a space one place up or down within its own pinned group, by renumbering `order` to match the display
 * positions. A move across the pinned boundary, or past either end, leaves the document unchanged.
 *
 * @param id the space to move.
 * @param up `true` to move it earlier, `false` for later.
 * @return the document with the new order values.
 */
fun SpacesDocument.moved(id: String, up: Boolean): SpacesDocument {
    val sorted = sortSpaces(spaces).toMutableList()
    val index = sorted.indexOfFirst { it.id == id }
    if (index < 0) return this
    val other = if (up) index - 1 else index + 1
    if (other !in sorted.indices || sorted[other].pinned != sorted[index].pinned) return this
    val tmp = sorted[index]
    sorted[index] = sorted[other]
    sorted[other] = tmp
    val position = sorted.mapIndexed { i, record -> record.id to i }.toMap()
    return copy(spaces = spaces.map { it.copy(order = position.getValue(it.id)) })
}
