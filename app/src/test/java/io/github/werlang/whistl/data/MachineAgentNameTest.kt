package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.domain.terminalDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

/** A terminal's name is carried with its own machine and its own desktop tab, never from another machine's document. */
class MachineAgentNameTest {

    /** The same tab id and terminal address on two machines get the name their own document gives the tab. */
    @Test
    fun tabNamesStayWithTheirMachine() {
        val terminal = Terminal(
            address = "t16b",
            name = null,
            argv = listOf("claude"),
            cwd = null,
            labels = mapOf("herdr.tab" to "tb1", "where" to "space / from where"),
            state = TerminalState.Live,
            status = AgentStatus.Working,
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
        val space = SpaceRecord(id = "sp1", name = "space", folder = "", pinned = false, order = 0)

        val one = MachineAgent("rpi5", Agent(terminal), space, tabName = "Claude Code")
        val two = MachineAgent("mac", Agent(terminal), space, tabName = "Codex")

        assertEquals("Claude Code", one.displayName)
        assertEquals("Codex", two.displayName)
    }

    /** A Whistl Rename changes the space label, not the Herdr `where` prefix; the raw desktop name keeps a split tab whole. */
    @Test
    fun aRenamedSpaceStillUsesTheRawHerdrNameForWhere() {
        val terminal = Terminal(
            address = "t16b",
            name = null,
            argv = listOf("sh"),
            cwd = null,
            labels = mapOf("where" to "gladcode2 / Fix / Deploy"),
            state = TerminalState.Live,
            status = AgentStatus.Working,
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
        val renamed = SpaceRecord(
            id = "sp1", name = "mine", folder = "", pinned = false, order = 0, nameOverride = true,
        )

        val agent = MachineAgent("rpi5", Agent(terminal), renamed, herdrSpaceName = "gladcode2")

        assertEquals("Fix / Deploy", agent.displayName)
    }

    /** A trailing separator in `where` falls back instead of using the space name, while a tab ending with `/` is kept. */
    @Test
    fun whereHandlesTrailingSeparatorAndTrailingSlashInTabName() {
        val base = Terminal(
            address = "t16b",
            name = null,
            argv = listOf("claude"),
            cwd = null,
            labels = emptyMap(),
            state = TerminalState.Live,
            status = AgentStatus.Working,
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

        val emptyTab = base.copy(where = "gladcode2 / ")
        assertEquals("claude", terminalDisplayName(emptyTab, spaceName = "gladcode2"))
        assertEquals("claude", terminalDisplayName(emptyTab, spaceName = null))

        val slashTab = base.copy(where = "gladcode2 / Test /")
        assertEquals("Test /", terminalDisplayName(slashTab, spaceName = "gladcode2"))
        assertEquals("Test /", terminalDisplayName(slashTab, spaceName = null))
    }
}
