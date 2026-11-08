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

    /** The detection script is byte-for-byte the `argv[2]` recorded in `exec_installed.json`. */
    @Test
    fun detectionScriptIsByteForByteTheFixtureScript() {
        val argv = Fixtures.load("exec_installed.json").request
            .getValue("params").jsonObject
            .getValue("argv").jsonArray
        val recorded = argv[2].jsonPrimitive.content

        assertEquals(recorded, KnownAgents.DETECTION_SCRIPT)
        assertEquals(listOf("sh", "-lc", recorded), KnownAgents.detectionArgv)
    }

    /** The table holds exactly the five agents of SPEC §5, each with its command as the argv. */
    @Test
    fun knownAgentsTableHoldsTheFiveSpecAgents() {
        assertEquals(listOf("claude", "codex", "gemini", "opencode", "aider"), KnownAgents.table.map { it.id })
        KnownAgents.table.forEach { assertEquals(listOf(it.id), it.argv) }
    }

    /** Blank lines and names outside the table are ignored; known names keep output order, once each. */
    @Test
    fun parseInstalledIgnoresBlankAndUnknownNames() {
        val parsed = KnownAgents.parseInstalled("\nopencode\n\nmystery-tool\n  claude  \nopencode\n")

        assertEquals(listOf("opencode", "claude"), parsed.map { it.id })
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
