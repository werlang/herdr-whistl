package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A terminal belongs to a space whichever of the two labels carries it: this app's, or the desktop's. */
class AgentSpaceLabelTest {
    /** The label this app writes. */
    @Test
    fun theAppsOwnLabelClaimsTheTerminal() {
        assertEquals("sp1", agent(mapOf(Agent.SPACE_LABEL to "sp1")).spaceId)
    }

    /** A terminal started from the Herdr desktop app carries `herdr.space`, and is claimed the same way (0.7.1). */
    @Test
    fun theDesktopsLabelClaimsTheTerminal() {
        assertEquals("sp7", agent(mapOf(Agent.HERDR_SPACE_LABEL to "sp7")).spaceId)
    }

    /** A terminal with neither label belongs to no space. */
    @Test
    fun aTerminalWithNoLabelHasNoSpace() {
        assertNull(agent(mapOf("where" to "edupage-automator / Claude Code")).spaceId)
    }

    private fun agent(labels: Map<String, String>): Agent =
        Agent(
            Terminal(
                address = "t1",
                name = null,
                argv = listOf("claude"),
                cwd = "/tmp",
                labels = labels,
                state = TerminalState.Live,
                status = AgentStatus.Idle,
                turn = 0,
                exitCode = null,
                createdMs = 0,
                finishedMs = null,
                title = "",
                pid = 1,
                signal = null,
                reason = null,
                keep = true,
            ),
        )
}
