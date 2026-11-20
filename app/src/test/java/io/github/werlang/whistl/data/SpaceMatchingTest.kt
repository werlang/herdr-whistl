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
    /** A terminal the desktop Herdr started carries `herdr.space`; its id is unknown here, so the folder decides (0.7.1). */
    @Test
    fun theDesktopsSpaceLabelFallsToTheFolder() {
        val terminal = agent("/home/u/web", mapOf(Agent.HERDR_SPACE_LABEL to "sp74d8f2e948b4779635327e0f4d87bc17"))

        assertEquals("sp_a", matchSpace(terminal, spaces)?.id)
    }

    /** A terminal started inside another space's folder belongs to the deeper one. */
    @Test
    fun theDeepestFolderWins() {
        val nested = listOf(space("sp_web", "/home/u/web"), space("sp_app", "/home/u/web/app"))
        val terminal = agent("/home/u/web/app/src")

        assertEquals("sp_app", matchSpace(terminal, nested)?.id)
    }

    /** A label the document does know still wins over the folder. */
    @Test
    fun aKnownLabelBeatsTheDeeperFolder() {
        val nested = listOf(space("sp_app", "/home/u/web/app"), space("sp_other", "/home/u/other"))
        val terminal = agent("/home/u/web/app", mapOf(Agent.SPACE_LABEL to "sp_other"))

        assertEquals("sp_other", matchSpace(terminal, nested)?.id)
    }

    /** The desktop's `herdr.tab` resolves to the space that owns the tab, and that beats the folder (0.7.3). */
    @Test
    fun theDesktopsTabLabelResolvesToItsParentSpace() {
        val terminal = agent("/home/u/api", mapOf(Agent.HERDR_TAB_LABEL to "tb1"))

        assertEquals("sp_a", matchSpace(terminal, spaces, tabSpaceId = { if (it == "tb1") "sp_a" else null })?.id)
    }

    /** A tab naming no known space falls through to the cwd rule. */
    @Test
    fun anUnknownTabFallsToCwd() {
        val terminal = agent("/home/u/web", mapOf(Agent.HERDR_TAB_LABEL to "tb_missing"))

        assertEquals("sp_a", matchSpace(terminal, spaces, tabSpaceId = { "sp_missing" })?.id)
    }

    /** The precedence is space label, then tab, then cwd, then no space. */
    @Test
    fun labelBeatsTabAndTabBeatsCwd() {
        val labelAndTab = agent(
            "/home/u/api",
            mapOf(Agent.HERDR_SPACE_LABEL to "sp_b", Agent.HERDR_TAB_LABEL to "tb1"),
        )

        assertEquals(
            "sp_b",
            matchSpace(labelAndTab, spaces, tabSpaceId = { "sp_a" })?.id,
        )
        assertEquals(
            "sp_a",
            matchSpace(agent("/home/u/api", mapOf(Agent.HERDR_TAB_LABEL to "tb1")), spaces, tabSpaceId = { "sp_a" })?.id,
        )
    }

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
    fun agentsScreenOrderPutsWhatNeedsYouFirstAndThenTheMostRecent() {
        fun entry(address: String, status: AgentStatus?, updatedMs: Long): MachineAgent =
            MachineAgent(
                "m1",
                Agent(agent(cwd = null).copy(address = address, status = status)),
                null,
                updatedMs,
            )

        val sorted = sortForAgentsScreen(
            listOf(
                entry("idle-old", AgentStatus.Idle, 1),
                entry("error", AgentStatus.Error, 2),
                entry("working-old", AgentStatus.Working, 3),
                entry("blocked", AgentStatus.Blocked, 4),
                entry("working-new", AgentStatus.Working, 5),
                entry("idle-new", AgentStatus.Idle, 6),
            ),
        )

        // What needs the user first (blocked, error), then the rest by recency — never by status rank (0.5.1).
        assertEquals(
            listOf("blocked", "error", "idle-new", "working-new", "working-old", "idle-old"),
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
