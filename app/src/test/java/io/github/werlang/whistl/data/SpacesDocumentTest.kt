package io.github.werlang.whistl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `whistl` document (SPEC §5): decode, modify and encode keep every field, and bad versions are typed errors. */
class SpacesDocumentTest {

    private val raw: JsonObject = Json.parseToJsonElement(
        """
        {
          "future_top": {"flag": true, "list": [1, 2]},
          "spaces": [
            {"id": "sp_0123456789abcdef", "name": "web-app", "folder": "~/code/web-app",
             "pinned": true, "order": 0, "future_field": "keep-me"}
          ],
          "v": 1
        }
        """.trimIndent(),
    ).let { it as JsonObject }

    /** Decode then encode gives back exactly what was read, unknown fields at both levels included. */
    @Test
    fun roundTripKeepsUnknownFieldsAtTopLevelAndInsideSpaces() {
        val document = SpacesDocument.fromJson(raw)

        assertEquals(raw, document.toJson())
        assertEquals("web-app", document.spaces.single().name)
        assertEquals(true, document.spaces.single().pinned)
    }

    /** A modification keeps the unknown fields of the document and of the spaces it did not touch. */
    @Test
    fun modifyingADocumentKeepsItsUnknownFields() {
        val document = SpacesDocument.fromJson(raw)
        val renamed = document.copy(
            spaces = document.spaces.map { it.copy(name = "web-app-2") },
        )

        val written = renamed.toJson()

        assertEquals("web-app-2", SpacesDocument.fromJson(written).spaces.single().name)
        assertEquals(raw.getValue("future_top"), written.getValue("future_top"))
        val rawSpace = raw.getValue("spaces").jsonArray.single().jsonObject
        val writtenSpace = written.getValue("spaces").jsonArray.single().jsonObject
        assertEquals(rawSpace.getValue("future_field"), writtenSpace.getValue("future_field"))
    }

    /** A `null` value (never written) reads as the empty version-1 document. */
    @Test
    fun nullValueIsTheEmptyVersionOneDocument() {
        assertEquals(SpacesDocument.empty(), SpacesDocument.fromJson(null))
        assertEquals(Json.parseToJsonElement("""{"spaces":[],"v":1}"""), SpacesDocument.empty().toJson())
    }

    /** A version other than 1 is a typed error, not a crash. */
    @Test
    fun versionTwoIsATypedError() {
        val future = Json.parseToJsonElement("""{"v":2,"spaces":[]}""")

        val error = assertThrows(UnsupportedDocumentVersion::class.java) {
            SpacesDocument.fromJson(future)
        }

        assertEquals(2L, error.version)
    }

    /** A space whose `pinned` is not a boolean is a typed error that names the field. */
    @Test
    fun malformedSpaceIsATypedErrorNamingTheField() {
        val bad = Json.parseToJsonElement(
            """{"v":1,"spaces":[{"id":"sp_0123456789abcdef","name":"x","folder":"/tmp","pinned":"yes","order":0}]}""",
        )

        val error = assertThrows(MalformedDocument::class.java) { SpacesDocument.fromJson(bad) }

        assertEquals(true, error.message.orEmpty().contains("pinned"))
    }
}
