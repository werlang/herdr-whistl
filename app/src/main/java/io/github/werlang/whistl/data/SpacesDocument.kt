package io.github.werlang.whistl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A problem with the `whistl` document's contents. Typed, so the caller can decide what to do; nothing here crashes.
 */
sealed class DocumentException(message: String) : RuntimeException(message)

/**
 * The document's `v` is not one this app reads (SPEC §5 reads only version 1).
 *
 * @property version the version found in the document.
 */
class UnsupportedDocumentVersion(val version: Long) :
    DocumentException("whistl document version $version is not supported")

/**
 * The document does not have the shape SPEC §5 defines. The message names the field, never the value.
 *
 * @param reason which part of the shape is wrong.
 */
class MalformedDocument(reason: String) : DocumentException("whistl document is malformed: $reason")

/**
 * One space as stored in the `whistl` document (SPEC §5): `id`, `name`, `folder`, `pinned`, `order`.
 *
 * Any other keys this app does not know are kept in [unknownFields] and written back unchanged. The record is
 * lenient on read for that reason: a newer app may have added fields.
 *
 * @property id the space id, `sp_` followed by 16 hex characters.
 * @property name the space name.
 * @property folder the folder on the machine.
 * @property pinned whether the space is pinned.
 * @property order the user's order within the machine.
 * @property unknownFields keys outside the five above, kept as sent.
 */
data class SpaceRecord(
    val id: String,
    val name: String,
    val folder: String,
    val pinned: Boolean,
    val order: Int,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    /** The record as a JSON object: the unknown fields plus the five known ones. */
    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(unknownFields)
        fields["id"] = JsonPrimitive(id)
        fields["name"] = JsonPrimitive(name)
        fields["folder"] = JsonPrimitive(folder)
        fields["pinned"] = JsonPrimitive(pinned)
        fields["order"] = JsonPrimitive(order)
        return JsonObject(fields)
    }

    companion object {
        private val KNOWN_KEYS = setOf("id", "name", "folder", "pinned", "order")

        /**
         * Reads one space object.
         *
         * @param element the space as sent.
         * @return the record, with its unknown fields kept.
         * @throws MalformedDocument when a known field is missing or has the wrong type.
         */
        fun fromJson(element: JsonElement): SpaceRecord {
            val obj = element as? JsonObject ?: throw MalformedDocument("a space is not an object")
            fun text(key: String): String = (obj[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.let { it.content }
                ?: throw MalformedDocument("space field $key is not a string")
            val pinned = (obj["pinned"] as? JsonPrimitive)?.booleanOrNull
                ?: throw MalformedDocument("space field pinned is not a boolean")
            val order = (obj["order"] as? JsonPrimitive)?.intOrNull
                ?: throw MalformedDocument("space field order is not an integer")
            return SpaceRecord(
                id = text("id"),
                name = text("name"),
                folder = text("folder"),
                pinned = pinned,
                order = order,
                unknownFields = JsonObject(obj.filterKeys { it !in KNOWN_KEYS }),
            )
        }
    }
}

/**
 * The `whistl` document one core keeps (SPEC §5): `{ "v": 1, "spaces": [ … ] }`.
 *
 * Read–modify–write keeps every field this app does not know, both at the top level ([unknownFields]) and inside each
 * space. [toJson] writes them back unchanged, so a round trip through this class loses nothing.
 *
 * @property spaces the spaces, in the order the document holds them.
 * @property unknownFields top-level keys other than `v` and `spaces`, kept as sent.
 */
data class SpacesDocument(
    val spaces: List<SpaceRecord>,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * The document as the JSON `doc.set` writes: the unknown fields, then `v` and `spaces`.
     *
     * @return the value to send.
     */
    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(unknownFields)
        fields["v"] = JsonPrimitive(VERSION)
        fields["spaces"] = JsonArray(spaces.map { it.toJson() })
        return JsonObject(fields)
    }

    companion object {
        /** The only document version this app reads and writes (SPEC §5). */
        const val VERSION: Int = 1

        private val KNOWN_KEYS = setOf("v", "spaces")

        /**
         * An empty version-1 document: what a never-written document reads as.
         *
         * @return a document with no spaces.
         */
        fun empty(): SpacesDocument = SpacesDocument(emptyList())

        /**
         * Reads the document from a `doc.get` value. `null` (never written) is the empty document.
         *
         * @param value the `value` of `doc.get`, or `null` when the document was never written.
         * @return the document, with its unknown fields kept.
         * @throws UnsupportedDocumentVersion when `v` is not [VERSION].
         * @throws MalformedDocument when the shape is wrong.
         */
        fun fromJson(value: JsonElement?): SpacesDocument {
            if (value == null || value is JsonNull) return empty()
            val obj = value as? JsonObject ?: throw MalformedDocument("the document is not an object")
            val versionElement = obj["v"] as? JsonPrimitive
                ?: throw MalformedDocument("the document has no version")
            val version = versionElement.longOrNull ?: throw MalformedDocument("the version is not a number")
            if (version != VERSION.toLong()) throw UnsupportedDocumentVersion(version)
            // A cast, not `jsonArray`: that accessor throws IllegalArgumentException for an object or a string (phase 6 P0-14).
            val spacesArray = obj["spaces"] as? JsonArray
                ?: throw MalformedDocument("the document has no spaces array")
            return SpacesDocument(
                spaces = spacesArray.map { SpaceRecord.fromJson(it) },
                unknownFields = JsonObject(obj.filterKeys { it !in KNOWN_KEYS }),
            )
        }
    }
}
