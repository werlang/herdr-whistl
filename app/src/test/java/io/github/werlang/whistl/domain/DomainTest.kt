package io.github.werlang.whistl.domain

import io.github.werlang.whistl.support.Fixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure domain rules: known agents, status and state parsing, and space membership. */
class DomainTest {

    /**
     * The detection call keeps the recorded shape (`sh -lc <script>`) and its script probes every agent in the
     * table. The fixture's own script is a recording and is not edited; what has to hold is that our script covers
     * the table (0.3.5: codex, gemini and aider were probed from a shorter list and then dropped by the parser).
     */
    @Test
    fun detectionScriptProbesEveryAgentInTheTable() {
        val argv = Fixtures.load("exec_installed.json").request
            .getValue("params").jsonObject
            .getValue("argv").jsonArray
        assertEquals("sh", argv[0].jsonPrimitive.content)
        assertEquals("-lc", argv[1].jsonPrimitive.content)

        assertEquals(listOf("sh", "-lc", KnownAgents.DETECTION_SCRIPT), KnownAgents.detectionArgv)

        // The probed list is read out of the script itself: a name added to the table but not to the script (or the
        // other way round) fails here.
        val probed = KnownAgents.DETECTION_SCRIPT
            .substringAfter("for a in ")
            .substringBefore(";")
            .split(" ")
            .filter { it.isNotBlank() }
        assertEquals(KnownAgents.probedIds, probed)
    }

    /** The table holds every agent this app names, each with its command as the argv. */
    @Test
    fun knownAgentsTableHoldsEveryNamedAgent() {
        assertEquals(
            listOf("claude", "codex", "gemini", "agy", "opencode", "cursor-agent", "aider", "goose", "amp", "crush",
                "qwen", "copilot", "droid", "cline"),
            KnownAgents.table.map { it.id },
        )
        KnownAgents.table.forEach { assertEquals(listOf(it.id), it.argv) }
    }

    /** Blank lines and names outside the table are ignored; known names keep output order, once each. */
    @Test
    fun parseInstalledKeepsUnknownNamesAndIgnoresBanners() {
        // A login banner is not an agent: only lines the script marked with its prefix are read (0.3.5 review).
        val parsed = KnownAgents.parseInstalled(
            "\nWelcome to Ubuntu 26.04 LTS\nAGENT:opencode\n\nAGENT:mystery-tool\n  AGENT:claude  \nAGENT:opencode\n",
        )

        // A probe result is a fact about the machine: an agent this table does not name is still offered, labelled
        // and started by its own command (0.3.5).
        assertEquals(listOf("opencode", "mystery-tool", "claude"), parsed.map { it.id })
        val unknown = parsed.single { it.id == "mystery-tool" }
        assertEquals("mystery-tool", unknown.displayName)
        assertEquals(listOf("mystery-tool"), unknown.argv)
    }

    /** Empty output (no agents) yields an empty list, not an error. */
    @Test
    fun parseInstalledOfEmptyOutputIsEmpty() {
        assertEquals(emptyList<KnownAgent>(), KnownAgents.parseInstalled(""))
    }

    /** A known status string maps to its enum value; `null` and unknown values map to `null`. */
    @Test
    fun agentStatusParsesKnownValuesAndNullsTheRest() {
        assertEquals(AgentStatus.Working, AgentStatus.fromWire("working"))
        assertEquals(AgentStatus.Blocked, AgentStatus.fromWire("blocked"))
        assertNull(AgentStatus.fromWire(null))
        assertNull(AgentStatus.fromWire("some-future-status"))
    }

    /** A known terminal state maps to its enum value; an unknown state maps to `null`, never a guess. */
    @Test
    fun terminalStateParsesKnownValuesAndNullsTheRest() {
        assertEquals(TerminalState.Live, TerminalState.fromWire("live"))
        assertEquals(TerminalState.Finished, TerminalState.fromWire("finished"))
        assertNull(TerminalState.fromWire("paused"))
    }

    /** An agent belongs to the space named by its `whistl.space` label, and to none when the label is absent. */
    @Test
    fun agentSpaceComesFromTheSpaceLabel() {
        val terminal = io.github.werlang.whistl.domain.Terminal(
            address = "t15s",
            name = null,
            argv = emptyList(),
            cwd = null,
            labels = mapOf("whistl.space" to "sp-demo"),
            state = TerminalState.Live,
            status = null,
            turn = 0,
            exitCode = null,
            createdMs = null,
            finishedMs = null,
            title = null,
            pid = null,
            signal = null,
            reason = null,
            keep = false,
        )

        assertEquals("sp-demo", Agent(terminal).spaceId)
        assertNull(Agent(terminal.copy(labels = emptyMap())).spaceId)
    }
}
