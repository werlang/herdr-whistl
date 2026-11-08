package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The pure space rules (SPEC §1): matching precedence, folder normalisation, display order, ids and document edits. */
class SpaceMatchingTest {

    private fun space(id: String, folder: String, pinned: Boolean = false, order: Int = 0) =
        SpaceRecord(id = id, name = id, folder = folder, pinned = pinned, order = order)

    private fun agent(cwd: String?, labels: Map<String, String> = emptyMap()): Terminal =
        Terminal(
            address = "t", name = null, argv = emptyList(), cwd = cwd, labels = labels,
            state = TerminalState.Live, status = AgentStatus.Working, turn = 0, exitCode = null,
            createdMs = null, finishedMs = null, title = null, pid = null, signal = null, reason = null, keep = false,
        )

    private val spaces = listOf(space("sp_a", "/home/u/web"), space("sp_b", "/home/u/api"))

    /** A label naming an existing space on the machine beats the cwd. */
    @Test
    fun labelBeatsCwd() {
        val terminal = agent(cwd = "/home/u/web", labels = mapOf(Agent.SPACE_LABEL to "sp_b"))

        assertEquals("sp_b", matchSpace(terminal, spaces)?.id)
    }

    /** With no label, a space whose folder equals the cwd matches; a cwd that matches nothing is "No space". */
    @Test
    fun cwdBeatsNoSpaceAndUnmatchedCwdIsNoSpace() {
        assertEquals("sp_a", matchSpace(agent(cwd = "/home/u/web"), spaces)?.id)
        assertNull(matchSpace(agent(cwd = "/tmp"), spaces))
        assertNull(matchSpace(agent(cwd = null), spaces))
    }

    /** A label that names no space on the machine falls through to the cwd rule. */
    @Test
    fun labelNamingAnUnknownSpaceFallsToCwd() {
        val terminal = agent(cwd = "/home/u/web", labels = mapOf(Agent.SPACE_LABEL to "sp_missing"))

        assertEquals("sp_a", matchSpace(terminal, spaces)?.id)
    }

    /** A trailing slash on either side is ignored when comparing folders; a lone `/` stays `/`. */
    @Test
    fun trailingSlashIsNormalised() {
        val withSlash = listOf(space("sp_s", "/home/u/web/"))

        assertEquals("sp_s", matchSpace(agent(cwd = "/home/u/web"), withSlash)?.id)
        assertEquals("sp_s", matchSpace(agent(cwd = "/home/u/web/"), listOf(space("sp_s", "/home/u/web")))?.id)
        assertEquals("/", normalizeFolder("/"))
        assertEquals("/home", normalizeFolder("/home//"))
    }

    /** `~` is not expanded: `~/x` matches only a folder written `~/x`. */
    @Test
    fun tildeIsNotExpanded() {
        val tilde = listOf(space("sp_t", "~/code/web-app"))

        assertNull(matchSpace(agent(cwd = "/home/u/code/web-app"), tilde))
        assertEquals("sp_t", matchSpace(agent(cwd = "~/code/web-app"), tilde)?.id)
    }

    /** Pinned spaces first, then by order; spaces that tie keep their document order. */
    @Test
    fun sortIsPinnedThenOrderWithTiesInDocumentOrder() {
        val document = listOf(
            space("sp_a", "/a", pinned = false, order = 2),
            space("sp_c", "/c", pinned = false, order = 0),
            space("sp_b", "/b", pinned = true, order = 0),
            space("sp_e", "/e", pinned = false, order = 0),
            space("sp_d", "/d", pinned = true, order = 1),
        )

        assertEquals(listOf("sp_b", "sp_d", "sp_c", "sp_e", "sp_a"), sortSpaces(document).map { it.id })
    }

    /** The default name of a space is the last segment of its folder. */
    @Test
    fun defaultNameIsTheLastFolderSegment() {
        assertEquals("web-app", defaultSpaceName("~/code/web-app"))
        assertEquals("web-app", defaultSpaceName("~/code/web-app/"))
        assertEquals("~", defaultSpaceName("~"))
    }

    /** A new space id is `sp_` followed by 16 lowercase hex characters, from the injected bytes. */
    @Test
    fun spaceIdsAreSixteenLowercaseHexCharacters() {
        val id = spaceIdsFrom { Random(3).nextBytes(it) }.next()

        assertTrue(Regex("sp_[0-9a-f]{16}").matches(id))
    }

    /** Moving a space swaps it with its neighbour in the same pinned group, and renumbers `order` to match. */
    @Test
    fun movedSwapsWithinItsPinnedGroupOnly() {
        val doc = SpacesDocument(
            listOf(
                space("sp_p1", "/p1", pinned = true, order = 0),
                space("sp_u1", "/u1", pinned = false, order = 0),
                space("sp_u2", "/u2", pinned = false, order = 1),
            ),
        )

        assertEquals(listOf("sp_u2", "sp_u1"), sortSpaces(doc.moved("sp_u2", up = true).spaces.filter { !it.pinned }).map { it.id })
        assertEquals("the first unpinned space cannot cross into the pinned group", doc, doc.moved("sp_u1", up = true))
        assertEquals("the last space cannot move down", doc, doc.moved("sp_u2", up = false))
    }

    /** The agents screen order: blocked, working, done, idle, error, then no status; equal ranks keep their order. */
    @Test
    fun agentsScreenOrderIsBlockedWorkingDoneIdleErrorThenNoStatus() {
        fun entry(address: String, status: AgentStatus?): MachineAgent =
            MachineAgent("m1", Agent(agent(cwd = null).copy(address = address, status = status)), null)

        val sorted = sortForAgentsScreen(
            listOf(
                entry("null-1", null),
                entry("idle", AgentStatus.Idle),
                entry("error", AgentStatus.Error),
                entry("done", AgentStatus.Done),
                entry("working", AgentStatus.Working),
                entry("blocked", AgentStatus.Blocked),
                entry("null-2", null),
            ),
        )

        assertEquals(
            listOf("blocked", "working", "done", "idle", "error", "null-1", "null-2"),
            sorted.map { it.agent.terminal.address },
        )
    }

    /** An edit to one space keeps that space's unknown fields in the written document. */
    @Test
    fun documentEditsKeepUnknownFields() {
        val unknown = JsonObject(mapOf("future" to JsonPrimitive(true)))
        val doc = SpacesDocument(listOf(SpaceRecord("sp_x", "x", "/x", false, 0, unknownFields = unknown)))

        val renamed = doc.mapSpace("sp_x") { it.copy(name = "y") }

        assertEquals("y", renamed.spaces.single().name)
        val written = renamed.toJson().getValue("spaces").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(true), written.getValue("future"))
    }
}
