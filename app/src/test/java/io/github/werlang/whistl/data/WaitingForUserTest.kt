package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one badge rule (`state == Live && status == Blocked`) and the screen order that follows from it (round p3r3).
 * A finished terminal is never waiting for the user, whatever status it kept.
 */
class WaitingForUserTest {

    private fun terminal(address: String, state: TerminalState?, status: AgentStatus?): Terminal = Terminal(
        address = address, name = null, argv = emptyList(), cwd = null, labels = emptyMap(),
        state = state, status = status, turn = 0, exitCode = null, createdMs = null, finishedMs = null,
        title = null, pid = null, signal = null, reason = null, keep = false,
    )

    private fun entry(address: String, state: TerminalState?, status: AgentStatus?): MachineAgent =
        MachineAgent("m1", Agent(terminal(address, state, status)), null)

    /** A live terminal whose status is blocked is waiting for the user. */
    @Test
    fun aLiveBlockedTerminalIsWaitingForTheUser() {
        assertTrue(terminal("t", TerminalState.Live, AgentStatus.Blocked).isWaitingForUser())
    }

    /** A finished terminal keeps the status it had, and is still not waiting: the badge must not count it. */
    @Test
    fun aFinishedTerminalWhoseLastStatusWasBlockedIsNotWaiting() {
        assertFalse(terminal("t", TerminalState.Finished, AgentStatus.Blocked).isWaitingForUser())
    }

    /** Only a live blocked terminal counts: every other combination of state and status does not. */
    @Test
    fun onlyLiveAndBlockedCounts() {
        assertFalse("live, idle", terminal("a", TerminalState.Live, AgentStatus.Idle).isWaitingForUser())
        assertFalse("live, working", terminal("b", TerminalState.Live, AgentStatus.Working).isWaitingForUser())
        assertFalse("live, error", terminal("c", TerminalState.Live, AgentStatus.Error).isWaitingForUser())
        assertFalse("live, no status", terminal("d", TerminalState.Live, null).isWaitingForUser())
        assertFalse("unknown state, blocked", terminal("e", null, AgentStatus.Blocked).isWaitingForUser())
    }

    /** A live idle agent comes before a finished blocked one, whatever the input order. */
    @Test
    fun aLiveIdleAgentComesBeforeAFinishedBlockedOne() {
        val sorted = sortForAgentsScreen(
            listOf(
                entry("finished-blocked", TerminalState.Finished, AgentStatus.Blocked),
                entry("live-idle", TerminalState.Live, AgentStatus.Idle),
            ),
        )

        assertEquals(listOf("live-idle", "finished-blocked"), sorted.map { it.agent.terminal.address })
    }

    /**
     * Live agents keep the SPEC §2 order; every agent that is not live comes after them, in its input order, and keeps
     * the status the reducer set.
     */
    @Test
    fun finishedAgentsSortAfterEveryLiveAgentInTheirInputOrder() {
        val sorted = sortForAgentsScreen(
            listOf(
                entry("fb", TerminalState.Finished, AgentStatus.Blocked),
                entry("li", TerminalState.Live, AgentStatus.Idle),
                entry("fi", TerminalState.Finished, AgentStatus.Idle),
                entry("lb", TerminalState.Live, AgentStatus.Blocked),
                entry("lw", TerminalState.Live, AgentStatus.Working),
            ),
        )

        assertEquals(listOf("lb", "lw", "li", "fb", "fi"), sorted.map { it.agent.terminal.address })
        assertEquals(AgentStatus.Blocked, sorted[3].agent.terminal.status)
    }
}
