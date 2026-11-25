package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.terminalDisplayName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The terminal-name fields of the real `terminal.list` (0.1.19): `where` arrives inside `labels`, not at the top level, and
 * `status` is the object whose `app` identifies the agent. A top-level `where` is still accepted for the `hc ls` sample.
 */
class TerminalNameWireTest {

    /** One terminal exactly as the RPC sends it: no name, `labels.where`, `labels.herdr.tab`, and the object status. */
    private val real = Json.parseToJsonElement(
        """
        {
          "address": "t16b",
          "argv": ["claude"],
          "cwd": "/home/workspaces/gladcode2",
          "labels": {
            "herdr.space": "sp9aed68",
            "herdr.tab": "tb987745",
            "where": "gladcode2 / Claude Code"
          },
          "name": null,
          "state": "live",
          "status": {"app": "claude-code", "state": "done"},
          "title": "dynamic title",
          "turn": 6
        }
        """.trimIndent(),
    ).jsonObject

    /** The RPC shape yields the label and the app; the name rule uses them with the space context. */
    @Test
    fun theRealRpcShapeCarriesWhereInsideLabelsAndAppInsideStatus() {
        val terminal = decodeTerminals(listOf(real)).terminals.single()

        assertNull("the RPC has no top-level where", terminal.where)
        assertEquals("gladcode2 / Claude Code", terminal.labels["where"])
        assertEquals("claude-code", terminal.agentApp)
        assertEquals("Claude Code", terminalDisplayName(terminal, spaceName = "gladcode2"))
    }

    /** The exact tab name beats every `where` split, including a tab whose own name contains " / ". */
    @Test
    fun theExactTabNameBeatsTheWhereString() {
        val terminal = decodeTerminals(listOf(real)).terminals.single()

        assertEquals("Fix / Deploy", terminalDisplayName(terminal, tabName = "Fix / Deploy", spaceName = "gladcode2"))
    }

    /** The dynamic `title` is never the name; without any other source the agent app is next. */
    @Test
    fun theDynamicTitleIsIgnoredAndTheAgentAppIsTheFallback() {
        val terminal = decodeTerminals(listOf(real)).terminals.single()
            .copy(labels = emptyMap(), where = null)

        assertEquals("claude-code", terminalDisplayName(terminal))
    }

    /** An object or array in the optional top-level `where` is ignored, and the terminal survives with `labels.where`. */
    @Test
    fun aMalformedTopLevelWhereKeepsTheTerminalAndItsLabel() {
        for (bad in listOf("""{"unexpected": true}""", """["nope"]""")) {
            val raw = Json.parseToJsonElement(
                """
                {
                  "address": "t16s",
                  "argv": ["codex"],
                  "cwd": "/home/workspaces/gladcode2",
                  "labels": {"herdr.space": "sp9aed68", "where": "gladcode2 / Codex"},
                  "name": null,
                  "state": "live",
                  "status": "done",
                  "where": $bad,
                  "turn": 27
                }
                """.trimIndent(),
            ).jsonObject

            val decoded = decodeTerminals(listOf(raw))
            val terminal = decoded.terminals.single()

            assertEquals("one bad where does not drop the terminal", 0, decoded.skipped)
            assertNull(terminal.where)
            assertEquals("Codex", terminalDisplayName(terminal, spaceName = "gladcode2"))
        }
    }

    /** An empty top-level `where` is not a name: `labels.where` is read next, not shadowed by the empty field. */
    @Test
    fun anEmptyTopLevelWhereFallsThroughToTheLabel() {
        val terminal = decodeTerminals(listOf(real)).terminals.single()
            .copy(where = "", labels = mapOf("where" to "gladcode2 / Codex"))

        assertEquals("Codex", terminalDisplayName(terminal, spaceName = "gladcode2"))
    }

    /** A blank tab after the separator is not a name; the explicit core name is the next fallback. */
    @Test
    fun aBlankWhereTabFallsToTheCoreName() {
        val terminal = decodeTerminals(listOf(real)).terminals.single()
            .copy(where = "gladcode2 / ", labels = emptyMap(), name = "core-name")

        assertEquals("core-name", terminalDisplayName(terminal, spaceName = "gladcode2"))
    }

    /** The top-level `where` the `hc ls` sample suggests is accepted when the RPC omits the label. */
    @Test
    fun aTopLevelWhereIsAcceptedForCompatibility() {
        val topLevel = Json.parseToJsonElement(
            """
            {
              "address": "t16r",
              "argv": ["agy"],
              "cwd": "/home/workspaces/gladcode2",
              "labels": {"herdr.space": "sp9aed68"},
              "name": null,
              "state": "live",
              "status": "done",
              "where": "gladcode2 / Antigravity",
              "turn": 19
            }
            """.trimIndent(),
        ).jsonObject

        val terminal = decodeTerminals(listOf(topLevel)).terminals.single()

        assertEquals("gladcode2 / Antigravity", terminal.where)
        assertEquals("Antigravity", terminalDisplayName(terminal, spaceName = "gladcode2"))
    }
}
