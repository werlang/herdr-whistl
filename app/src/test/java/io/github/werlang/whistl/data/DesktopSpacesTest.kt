package io.github.werlang.whistl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the Herdr desktop's spaces off the core, and adopting the ones this app does not have yet (0.7.3). */
class DesktopSpacesTest {

    /** The desktop's document as it really is: `spaces`, each with an id, a folder, a name and its tabs. */
    @Test
    fun itReadsTheDesktopsSpaces() {
        val desktop = parseDesktopSpaces(document("""
            {"spaces": [
              {"folder": "/Users/p/workspaces/ifsul/edupage-automator", "id": "sp74d8f2e948b4779635327e0f4d87bc17",
               "name": "edupage-automator", "tabs": [{"id": "tbfbf9384d20"}]}
            ]}
        """))

        assertEquals(listOf(DesktopSpace("sp74d8f2e948b4779635327e0f4d87bc17", "edupage-automator", "/Users/p/workspaces/ifsul/edupage-automator")), desktop)
    }

    /** The desktop's shape is not this app's to enforce: a document of another shape yields nothing, and never throws. */
    @Test
    fun aDocumentOfAnotherShapeYieldsNothing() {
        assertTrue(parseDesktopSpaces(null).isEmpty())
        assertTrue(parseDesktopSpaces(document("""{"spaces": "nope"}""")).isEmpty())
        assertTrue(parseDesktopSpaces(document("""{"other": []}""")).isEmpty())
        assertTrue(parseDesktopSpaces(document("""{"spaces": [{"id": "sp1"}, {"folder": "/tmp"}]}""")).isEmpty())
    }

    /** A space without a name takes the last segment of its folder. */
    @Test
    fun aSpaceWithoutANameTakesItsFolder() {
        val desktop = parseDesktopSpaces(document("""{"spaces": [{"id": "sp1", "folder": "/home/p/web/"}]}"""))

        assertEquals("web", desktop.single().name)
    }

    /** An adopted space keeps the desktop's id, so the desktop's `herdr.space` label resolves to it. */
    @Test
    fun adoptionKeepsTheDesktopsId() {
        val adopted = adoptedSpaces(own = emptyList(), desktop = listOf(DesktopSpace("sp74d8", "edupage", "/p/edupage")))

        assertEquals("sp74d8", adopted.single().id)
        assertEquals("/p/edupage", adopted.single().folder)
    }

    /** A folder this app already has is not adopted twice, whatever its id, and a trailing slash does not fool it. */
    @Test
    fun aFolderAlreadyHereIsNotAdopted() {
        val own = listOf(record("sp_aaa", "/p/edupage/"))
        val desktop = listOf(DesktopSpace("sp74d8", "edupage", "/p/edupage"), DesktopSpace("sp75x", "other", "/p/other"))

        assertEquals(listOf("sp75x"), adoptedSpaces(own, desktop).map { it.id })
    }

    /** An id already here is not adopted again, even under another folder. */
    @Test
    fun anIdAlreadyHereIsNotAdopted() {
        val own = listOf(record("sp74d8", "/p/moved"))
        val desktop = listOf(DesktopSpace("sp74d8", "edupage", "/p/edupage"))

        assertTrue(adoptedSpaces(own, desktop).isEmpty())
    }

    /** Adopted spaces come after the ones already here, in the desktop's order. */
    @Test
    fun adoptedSpacesAreAppendedInOrder() {
        val own = listOf(record("sp_aaa", "/p/one"))
        val desktop = listOf(DesktopSpace("sp75x", "two", "/p/two"), DesktopSpace("sp76y", "three", "/p/three"))

        val adopted = adoptedSpaces(own, desktop)

        assertEquals(listOf("/p/two", "/p/three"), adopted.map { it.folder })
        assertEquals(listOf(1, 2), adopted.map { it.order })
    }

    private fun record(id: String, folder: String) =
        SpaceRecord(id = id, name = folder.substringAfterLast('/'), folder = folder, pinned = false, order = 0)

    private fun document(json: String) = Json.parseToJsonElement(json) as JsonObject
}
