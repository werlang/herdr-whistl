package io.github.werlang.whistl.domain

import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Finishing is the move out of work into a state where nobody is waited on: an agent that asks has not finished. */
class FinishedAgentsTest {
    /** Work that ends is announced. */
    @Test
    fun workingToIdleIsFinished() {
        val agent = agent("t1", AgentStatus.Idle)

        val finished = FinishedAgents.between(mapOf("m/t1" to AgentStatus.Working), listOf(agent))

        assertEquals(listOf("t1"), finished.map { it.agent.terminal.address })
    }

    /** An agent that stops to ask for the user has not finished. */
    @Test
    fun workingToBlockedIsNotFinished() {
        val agent = agent("t1", AgentStatus.Blocked)

        assertEquals(emptyList<MachineAgent>(), FinishedAgents.between(mapOf("m/t1" to AgentStatus.Working), listOf(agent)))
    }

    /** An agent that was already idle is not announced again. */
    @Test
    fun idleToIdleIsNotFinished() {
        val agent = agent("t1", AgentStatus.Idle)

        assertEquals(emptyList<MachineAgent>(), FinishedAgents.between(mapOf("m/t1" to AgentStatus.Idle), listOf(agent)))
    }

    /** A terminal that is gone is finished whatever its last status said. */
    @Test
    fun aFinishedTerminalIsFinished() {
        val agent = agent("t1", AgentStatus.Working, state = TerminalState.Finished)

        assertEquals(1, FinishedAgents.between(mapOf("m/t1" to AgentStatus.Working), listOf(agent)).size)
    }

    private fun agent(address: String, status: AgentStatus?, state: TerminalState = TerminalState.Live): MachineAgent =
        MachineAgent(
            machineId = "m",
            agent = Agent(
                Terminal(
                    address = address,
                    name = address,
                    argv = listOf("sh"),
                    cwd = "/tmp",
                    labels = emptyMap(),
                    state = state,
                    status = status,
                    turn = 0,
                    exitCode = null,
                    createdMs = 0,
                    finishedMs = null,
                    title = "",
                    pid = 1,
                    signal = null,
                    reason = null,
                    keep = false,
                ),
            ),
            space = null,
        )
}
