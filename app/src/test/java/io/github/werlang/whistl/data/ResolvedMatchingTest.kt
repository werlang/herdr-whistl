package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.dataOf
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.terminalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules behind folder resolution (phase 4 decision 1): cwd matching against a resolved path, tilde expansion, the
 * reading of probe output, and the probe filter in the events reducer.
 */
class ResolvedMatchingTest {

    private fun agent(cwd: String?, labels: Map<String, String> = emptyMap()): Terminal = Terminal(
        address = "t", name = null, argv = emptyList(), cwd = cwd, labels = labels, state = TerminalState.Live,
        status = null, turn = 0, exitCode = null, createdMs = null, finishedMs = null, title = null, pid = null,
        signal = null, reason = null, keep = false,
    )

    private fun space(id: String, folder: String): SpaceRecord = SpaceRecord(id, id, folder, false, 0)

    /** A space whose folder is `~/...` matches the agent by the path it resolved to, not by the typed folder. */
    @Test
    fun theResolvedPathDecidesTheCwdMatch() {
        val spaces = listOf(space("sp_t", "~/code/web-app"))
        val resolved = { _: SpaceRecord -> "/home/u/code/web-app" }

        assertEquals("sp_t", matchSpace(agent(cwd = "/home/u/code/web-app/"), spaces, resolved)?.id)
        assertNull(matchSpace(agent(cwd = "~/code/web-app"), spaces, resolved))
    }

    /** A space that did not resolve never matches by cwd, whatever the agent's cwd is. */
    @Test
    fun anUnresolvedSpaceNeverMatchesByCwd() {
        val spaces = listOf(space("sp_t", "~/code/web-app"))
        val unresolved = { _: SpaceRecord -> null }

        assertNull(matchSpace(agent(cwd = "~/code/web-app"), spaces, unresolved))
        assertNull(matchSpace(agent(cwd = "/home/u/code/web-app"), spaces, unresolved))
    }

    /** A label still matches a space whose folder did not resolve. */
    @Test
    fun aLabelStillMatchesAnUnresolvedSpace() {
        val spaces = listOf(space("sp_t", "~/code/web-app"))
        val terminal = agent(cwd = "/tmp", labels = mapOf(Agent.SPACE_LABEL to "sp_t"))

        assertEquals("sp_t", matchSpace(terminal, spaces) { null }?.id)
    }

    /** `~` becomes the home directory; `~/x` becomes home plus `/x`; a trailing slash on home is not doubled. */
    @Test
    fun tildeExpansionKeepsOneSlash() {
        assertEquals("/home/u", FolderResolver.expandTilde("~", "/home/u"))
        assertEquals("/", FolderResolver.expandTilde("~", "/"))
        assertEquals("/x", FolderResolver.expandTilde("~/x", "/"))
        assertEquals("/home/u/x", FolderResolver.expandTilde("~/x", "/home/u/"))
        assertEquals("/home/u/code/web-app", FolderResolver.expandTilde("~/code/web-app", "/home/u"))
    }

    /** The answer is the last line with text; blank lines and CR characters do not count. */
    @Test
    fun theLastNonBlankLineIsTheAnswer() {
        assertEquals("/a", FolderResolver.lastNonBlankLine("/a\n   \n"))
        assertEquals("/b", FolderResolver.lastNonBlankLine("x\r\n/b\r\n"))
        assertNull(FolderResolver.lastNonBlankLine(""))
        assertNull(FolderResolver.lastNonBlankLine("  \n\t\n"))
    }

    /** A terminal is a probe by its label, and only by it. */
    @Test
    fun aProbeIsATerminalWithTheProbeLabel() {
        assertTrue(agent(cwd = null, labels = mapOf(PROBE_LABEL to "1")).isProbe())
        assertFalse(agent(cwd = null, labels = mapOf(Agent.SPACE_LABEL to "sp_a")).isProbe())
    }

    /** A probe created on the events channel never enters the agents' map. */
    @Test
    fun aCreatedProbeIsNotAddedByTheReducer() {
        val probe = event(eventLine("created", "tp1", 3, terminalJson("tp1", labels = mapOf(PROBE_LABEL to "1"))))

        assertEquals(emptyMap<String, Terminal>(), AgentEventReducer.apply(emptyMap(), probe))
    }

    /** A terminal that gains the probe label leaves the agents' map. */
    @Test
    fun aTerminalThatBecomesAProbeLeavesTheMap() {
        val created = event(eventLine("created", "t1", 3, terminalJson("t1")))
        val relabelled = event(eventLine("labels", "t1", 4, dataOf("labels" to labelsJson(PROBE_LABEL to "1"))))

        val before = AgentEventReducer.apply(emptyMap(), created)
        assertEquals(setOf("t1"), before.keys)
        assertEquals(emptySet<String>(), AgentEventReducer.apply(before, relabelled).keys)
    }

    /** A `missed` snapshot that carries probes keeps only the agents. */
    @Test
    fun aMissedSnapshotDropsProbes() {
        val missed = event(
            eventLine(
                "missed", null, 9,
                dataOf(
                    "terminals" to JsonArray(
                        listOf(
                            terminalJson("t1"),
                            terminalJson("tp2", labels = mapOf(PROBE_LABEL to "1")),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("t1"), AgentEventReducer.apply(emptyMap(), missed).keys)
    }

    private fun event(line: String): HcEvent =
        HcEvent.fromParams(Json.parseToJsonElement(line).jsonObject.getValue("params"))

    private fun labelsJson(vararg entries: Pair<String, String>): JsonElement =
        Json.parseToJsonElement(entries.joinToString(prefix = "{", postfix = "}") { "\"${it.first}\":\"${it.second}\"" })
}
