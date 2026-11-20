package io.github.werlang.whistl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The document the Herdr desktop app keeps its spaces in, on the same core (0.7.3).
 *
 * Reading it is what makes the two apps agree without the user typing anything: a space made on the desktop shows up here,
 * with the desktop's own id, so the terminals the desktop started — labelled `herdr.space` with that id — resolve to it.
 */
const val HERDR_DOCUMENT: String = "herdr-app"

/**
 * One space of the desktop's document.
 *
 * @property id the desktop's space id, `sp` followed by 32 hex characters.
 * @property name the name shown in the desktop app.
 * @property folder the folder the space points at, as typed there.
 */
data class DesktopSpace(val id: String, val name: String, val folder: String)

/**
 * Reads the spaces out of the desktop's document.
 *
 * A document that is missing, not an object, or holds no `spaces` array yields nothing. A space without an id or a folder is
 * skipped: it could not be resolved here. A space without a name takes the last segment of its folder. Nothing throws: this
 * document belongs to another app, and its shape is not this app's to enforce.
 *
 * @param value the `value` of `doc.get` for [HERDR_DOCUMENT], or `null`.
 * @return the desktop's spaces, in its order.
 */
fun parseDesktopSpaces(value: JsonElement?): List<DesktopSpace> {
    val root = value as? JsonObject ?: return emptyList()
    val spaces = root["spaces"] as? JsonArray ?: return emptyList()
    return spaces.mapNotNull { element ->
        val space = element as? JsonObject ?: return@mapNotNull null
        val id = (space["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val folder = (space["folder"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = (space["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: folder.trimEnd('/').substringAfterLast('/')
        DesktopSpace(id = id, name = name, folder = folder)
    }
}

/**
 * Reads the desktop's tab-to-space map out of its document.
 *
 * Every space may hold `tabs`, each with an id. A terminal the desktop starts in a tab carries `herdr.tab=<tab id>`, so this
 * map is what lets such a terminal resolve to the space that owns the tab even when its `herdr.space` label does not name a
 * space here. A tab of a space without an id, a tab without an id, and a document of another shape are skipped; nothing
 * throws.
 *
 * @param value the `value` of `doc.get` for [HERDR_DOCUMENT], or `null`.
 * @return the parent space id of each tab, keyed by tab id.
 */
fun parseDesktopTabSpaces(value: JsonElement?): Map<String, String> {
    val root = value as? JsonObject ?: return emptyMap()
    val spaces = root["spaces"] as? JsonArray ?: return emptyMap()
    val tabs = LinkedHashMap<String, String>()
    for (element in spaces) {
        val space = element as? JsonObject ?: continue
        val spaceId = (space["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
        val spaceTabs = space["tabs"] as? JsonArray ?: continue
        for (tabElement in spaceTabs) {
            val tab = tabElement as? JsonObject ?: continue
            val tabId = (tab["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            tabs[tabId] = spaceId
        }
    }
    return tabs
}

/**
 * The desktop's spaces that this app does not have yet.
 *
 * Only the id decides: a space is already here when its id is here. The folder does not, because the desktop may keep
 * several spaces on the same folder — each of them is a space of its own there, and dropping all but one would hide the
 * others and their terminals. Adopted spaces are appended in the desktop's order, so the document reads in the order the
 * user made them.
 *
 * @param own the spaces this app's document holds.
 * @param desktop the spaces of the desktop's document.
 * @return the records to add, empty when there is nothing new.
 */
fun adoptedSpaces(own: List<SpaceRecord>, desktop: List<DesktopSpace>): List<SpaceRecord> {
    val ids = own.map { it.id }.toSet()
    return desktop
        .filterNot { it.id in ids }
        .distinctBy { it.id }
        .mapIndexed { index, space ->
            SpaceRecord(
                id = space.id,
                name = space.name,
                folder = space.folder,
                pinned = false,
                order = own.size + index,
            )
        }
}
