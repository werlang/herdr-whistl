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
        // An id is enough: the folder is optional, and a record without one is still a space with its own name.
        val partial = parseDesktopSpaces(document("""{"spaces": [{"id": "sp1"}, {"folder": "/tmp"}]}"""))
        assertEquals(listOf(DesktopSpace("sp1", "sp1", "")), partial)
    }

    /**
     * The rpi5 document: nine spaces with `folder: ""` and their own names. The folder is not the name, and it is not a reason
     * to drop a space (the defect this fixes).
     */
    @Test
    fun spacesWithAnEmptyFolderKeepTheirDocumentNames() {
        val desktop = parseDesktopSpaces(document("""
            {"spaces": [
              {"folder": "", "id": "sp9aed68", "name": "gladcode2", "tabs": [{"id": "tb1", "name": "Claude Code"}]},
              {"folder": "", "id": "sp646103", "name": "cv-mapa", "tabs": []},
              {"folder": "", "id": "sp6ad746", "name": "home", "tabs": []},
              {"folder": "", "id": "sp901bcf", "name": "user-skills", "tabs": []},
              {"folder": "/root", "id": "sp820ec2", "name": "node-aec", "tabs": []},
              {"folder": "", "id": "spc90b66", "name": "whatsbot", "tabs": []},
              {"folder": "", "id": "spba1087", "name": "picole", "tabs": []},
              {"folder": "", "id": "sp22771c", "name": "node-aec production", "tabs": []},
              {"folder": "", "id": "sp853226", "name": "whatsbot prod", "tabs": []}
            ]}
        """))

        assertEquals(9, desktop.size)
        assertEquals(
            listOf(
                "gladcode2", "cv-mapa", "home", "user-skills", "node-aec",
                "whatsbot", "picole", "node-aec production", "whatsbot prod",
            ),
            desktop.map { it.name },
        )
        assertEquals(listOf("", "", "", "", "/root", "", "", "", ""), desktop.map { it.folder })
    }

    /** A space whose name is missing gets its id, never a path segment: the folder is an attribute, not a label. */
    @Test
    fun aSpaceWithoutANameTakesItsIdNotItsFolder() {
        val desktop = parseDesktopSpaces(document("""{"spaces": [{"id": "sp1", "folder": "/home/p/web/"}]}"""))

        assertEquals("sp1", desktop.single().name)
    }

    /** An adopted space keeps the desktop's id, so the desktop's `herdr.space` label resolves to it. */
    @Test
    fun adoptionKeepsTheDesktopsId() {
        val adopted = adoptedSpaces(own = emptyList(), desktop = listOf(DesktopSpace("sp74d8", "edupage", "/p/edupage")))

        assertEquals("sp74d8", adopted.single().id)
        assertEquals("/p/edupage", adopted.single().folder)
    }

    /** A folder this app already has is not a reason to skip a desktop space: only its id is (0.7.3). */
    @Test
    fun spacesThatShareAFolderAreAllAdopted() {
        val own = listOf(record("sp_aaa", "/p/edupage/"))
        val desktop = listOf(DesktopSpace("sp74d8", "edupage", "/p/edupage"), DesktopSpace("sp75x", "other", "/p/other"))

        assertEquals(listOf("sp74d8", "sp75x"), adoptedSpaces(own, desktop).map { it.id })
    }

    /** Two desktop spaces may keep the same folder: each one is a space there, so both are adopted. */
    @Test
    fun adoptingMultipleDesktopSpacesThatShareTheSameFolder() {
        val desktop = listOf(
            DesktopSpace("sp74d8", "web-one", "/p/shared"),
            DesktopSpace("sp75x", "web-two", "/p/shared"),
        )

        val adopted = adoptedSpaces(own = emptyList(), desktop = desktop)

        assertEquals(listOf("sp74d8", "sp75x"), adopted.map { it.id })
        assertEquals(listOf("/p/shared", "/p/shared"), adopted.map { it.folder })
        assertEquals(listOf(0, 1), adopted.map { it.order })
    }

    /** The desktop's tab ids map to the space that owns them; blank or missing ids are skipped. */
    @Test
    fun itReadsTheDesktopsTabSpaces() {
        val tabs = parseDesktopTabSpaces(document("""
            {"spaces": [
              {"id": "sp1", "folder": "/p", "tabs": [{"id": "tb1"}, {"id": "tb2"}]},
              {"id": "sp2", "folder": "/p", "tabs": [{"id": "tb3"}]},
              {"id": "", "folder": "/q", "tabs": [{"id": "tb4"}]},
              {"id": "sp3", "folder": "/r", "tabs": [{"id": ""}, {"no_id": true}]}
            ]}
        """))

        assertEquals(mapOf("tb1" to "sp1", "tb2" to "sp1", "tb3" to "sp2"), tabs)
    }

    /** The tab map is read as leniently as the spaces: another shape yields nothing, and never throws. */
    @Test
    fun aTabDocumentOfAnotherShapeYieldsNothing() {
        assertTrue(parseDesktopTabSpaces(null).isEmpty())
        assertTrue(parseDesktopTabSpaces(document("""{"spaces": "nope"}""")).isEmpty())
        assertTrue(parseDesktopTabSpaces(document("""{"spaces": [{"id": "sp1", "tabs": "nope"}]}""")).isEmpty())
        assertTrue(parseDesktopTabSpaces(document("""{"spaces": [{"id": "sp1", "tabs": ["nope"]}]}""")).isEmpty())
    }

    /** Each tab keeps its own name and the space that owns it, for a terminal that carries the tab id. */
    @Test
    fun tabsKeepTheirNamesAndOwners() {
        val tabs = parseDesktopTabs(document("""
            {"spaces": [
              {"id": "sp1", "tabs": [{"id": "tb1", "name": "Claude Code"}, {"id": "tb2"}]},
              {"id": "sp2", "tabs": [{"id": "tb3", "name": "Fix / Deploy"}]}
            ]}
        """))

        assertEquals(mapOf("tb1" to DesktopTab("sp1", "Claude Code"), "tb2" to DesktopTab("sp1", null), "tb3" to DesktopTab("sp2", "Fix / Deploy")), tabs)
        assertEquals(mapOf("tb1" to "sp1", "tb2" to "sp1", "tb3" to "sp2"), parseDesktopTabSpaces(document("""
            {"spaces": [
              {"id": "sp1", "tabs": [{"id": "tb1", "name": "Claude Code"}, {"id": "tb2"}]},
              {"id": "sp2", "tabs": [{"id": "tb3", "name": "Fix / Deploy"}]}
            ]}
        """)))
    }

    /**
     * A desktop-backed record follows the desktop's name and folder. A legacy record without the override marker is treated as
     * imported and aligned on the first refresh; a Rename marked after the upgrade keeps its name but still takes the folder.
     */
    @Test
    fun adoptedNamesAndFoldersFollowTheDesktopUntilRenamedHere() {
        val adopted = SpaceRecord("sp1", "old", "/p", false, 0, herdrName = "old")
        val legacy = SpaceRecord("sp2", "legacy", "/old", pinned = true, order = 3, herdrName = null)
        val renamed = SpaceRecord(
            "sp3", "mine", "/x", pinned = true, order = 1, herdrName = null, nameOverride = true,
        )
        val desktop = listOf(
            DesktopSpace("sp1", "new", "/p"),
            DesktopSpace("sp2", "desktop", ""),
            DesktopSpace("sp3", "other", "/new"),
        )

        val synced = syncDesktopSpaces(listOf(adopted, legacy, renamed), desktop)

        assertEquals(listOf("new", "desktop", "mine"), synced.map { it.name })
        assertEquals(listOf("/p", "", "/new"), synced.map { it.folder })
        assertEquals(listOf("new", "desktop", null), synced.map { it.herdrName })
        assertEquals(listOf(true, true), listOf(synced[1].pinned, synced[2].pinned))
        assertEquals(listOf(3, 1), listOf(synced[1].order, synced[2].order))
    }

    /** A repeated id in the desktop document keeps the first entry in both adoption and later syncs, never alternating. */
    @Test
    fun aDuplicatedDesktopIdKeepsTheSameFirstEntry() {
        val desktop = listOf(
            DesktopSpace("sp1", "first", "/a"),
            DesktopSpace("sp1", "second", "/b"),
        )

        val adopted = adoptedSpaces(emptyList(), desktop)
        val synced = syncDesktopSpaces(listOf(SpaceRecord("sp1", "old", "/old", false, 0, herdrName = "old")), desktop)

        assertEquals(listOf("first"), adopted.map { it.name })
        assertEquals(listOf("/a"), adopted.map { it.folder })
        assertEquals(listOf("first"), synced.map { it.name })
        assertEquals(listOf("/a"), synced.map { it.folder })
    }

    /** Numbers, booleans and objects in the desktop document are not ids, names or folders; other valid records survive. */
    @Test
    fun nonStringDesktopFieldsDoNotBecomeText() {
        val desktop = parseDesktopSpaces(document("""
            {"spaces": [
              {"id": 123, "name": true, "folder": false},
              {"id": "sp2", "name": 42, "folder": {}},
              {"id": "sp3", "name": "ok", "folder": ""}
            ]}
        """))

        assertEquals(listOf(DesktopSpace("sp2", "sp2", ""), DesktopSpace("sp3", "ok", "")), desktop)
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
