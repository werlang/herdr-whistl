package io.github.werlang.whistl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * @property name the name shown in the desktop app. It is the label; a missing name falls back to the id, never to the folder.
 * @property folder the folder the space points at, as typed there; `""` when the desktop has none for it.
 */
data class DesktopSpace(val id: String, val name: String, val folder: String)

/**
 * One tab of a desktop space.
 *
 * @property spaceId the space that owns the tab.
 * @property name the tab's own name, or `null` when the desktop did not give it one.
 */
data class DesktopTab(val spaceId: String, val name: String?)

/**
 * Reads the spaces out of the desktop's document.
 *
 * A document that is missing, not an object, or holds no `spaces` array yields nothing. A space without an id is skipped:
 * it has no identity to keep. The folder is optional — on rpi5 the desktop keeps every space with `folder: ""` — and a
 * space with no name takes its id, not a path segment. Nothing throws: this document belongs to another app, and its shape
 * is not this app's to enforce.
 *
 * @param value the `value` of `doc.get` for [HERDR_DOCUMENT], or `null`.
 * @return the desktop's spaces, in its order.
 */
fun parseDesktopSpaces(value: JsonElement?): List<DesktopSpace> {
    val root = value as? JsonObject ?: return emptyList()
    val spaces = root["spaces"] as? JsonArray ?: return emptyList()
    return spaces.mapNotNull { element ->
        val space = element as? JsonObject ?: return@mapNotNull null
        val id = space.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val folder = space.text("folder").orEmpty()
        val name = space.text("name")?.takeIf { it.isNotBlank() } ?: id
        DesktopSpace(id = id, name = name, folder = folder)
    }
}

/**
 * Reads the desktop's tabs out of its document.
 *
 * Every space may hold `tabs`, each with an id and usually a name. A terminal the desktop starts in a tab carries
 * `herdr.tab=<tab id>`, so this map resolves it to the space that owns the tab and to the name the user sees there. A tab of
 * a space without an id, a tab without an id, and a document of another shape are skipped; nothing throws.
 *
 * @param value the `value` of `doc.get` for [HERDR_DOCUMENT], or `null`.
 * @return each tab, keyed by tab id.
 */
fun parseDesktopTabs(value: JsonElement?): Map<String, DesktopTab> {
    val root = value as? JsonObject ?: return emptyMap()
    val spaces = root["spaces"] as? JsonArray ?: return emptyMap()
    val tabs = LinkedHashMap<String, DesktopTab>()
    for (element in spaces) {
        val space = element as? JsonObject ?: continue
        val spaceId = space.text("id")?.takeIf { it.isNotBlank() } ?: continue
        val spaceTabs = space["tabs"] as? JsonArray ?: continue
        for (tabElement in spaceTabs) {
            val tab = tabElement as? JsonObject ?: continue
            val tabId = tab.text("id")?.takeIf { it.isNotBlank() } ?: continue
            val name = tab.text("name")?.takeIf { it.isNotBlank() }
            tabs[tabId] = DesktopTab(spaceId = spaceId, name = name)
        }
    }
    return tabs
}

/** A string member of a desktop document object; a number, boolean, object or array is not text and reads as absent. */
private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The tab-to-space map of [parseDesktopTabs].
 *
 * @param value the `value` of `doc.get` for [HERDR_DOCUMENT], or `null`.
 * @return the parent space id of each tab, keyed by tab id.
 */
fun parseDesktopTabSpaces(value: JsonElement?): Map<String, String> =
    parseDesktopTabs(value).mapValues { (_, tab) -> tab.spaceId }

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
    return indexDesktopSpaces(desktop.filterNot { it.id in ids })
        .values
        .mapIndexed { index, space ->
            SpaceRecord(
                id = space.id,
                name = space.name,
                folder = space.folder,
                pinned = false,
                order = own.size + index,
                herdrName = space.name,
                nameOverride = false,
            )
        }
}

/**
 * The records of spaces the desktop document also has, with the desktop's current folder and, unless the user renamed the
 * space here, its current name.
 *
 * A space is desktop-backed when its id is in the desktop's document. The folder is always taken from there: this app has no
 * action to edit an existing folder, so a desktop change (including to `""`) is the only source, and the same id must not
 * keep a stale path that would match by `cwd`. Pinned state and order stay the user's.
 *
 * `nameOverride` is the explicit Rename marker. When it is set, the name and the rest of the record are left alone. Without
 * it, the name follows the desktop — including for a record made before this marker existed. That legacy case cannot be told
 * apart from an old imported name, so the desktop name wins on the first refresh; a Rename made after the upgrade is marked
 * and never overwritten. A duplicate id in the desktop document keeps the first entry, the same rule [adoptedSpaces] uses, so
 * a repeated refresh cannot alternate between two names.
 *
 * @param own the spaces this app's document holds.
 * @param desktop the spaces of the desktop's document.
 * @return the records to keep, in the same order.
 */
fun syncDesktopSpaces(own: List<SpaceRecord>, desktop: List<DesktopSpace>): List<SpaceRecord> {
    val byId = indexDesktopSpaces(desktop)
    return own.map { record ->
        val source = byId[record.id] ?: return@map record
        if (record.nameOverride) {
            if (record.folder == source.folder) record else record.copy(folder = source.folder)
        } else {
            record.copy(name = source.name, folder = source.folder, herdrName = source.name)
        }
    }
}

/**
 * Indexes desktop spaces by id with the first entry winning, the same rule [adoptedSpaces] and [syncDesktopSpaces] use, so a
 * duplicated id cannot alternate between two names across reads.
 *
 * @param desktop the spaces of the desktop's document, in its order.
 * @return the first space of each id.
 */
fun indexDesktopSpaces(desktop: List<DesktopSpace>): Map<String, DesktopSpace> {
    val byId = LinkedHashMap<String, DesktopSpace>()
    for (space in desktop) byId.putIfAbsent(space.id, space)
    return byId
}
