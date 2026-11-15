package io.github.werlang.whistl.domain

import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.isWaitingForUser

/**
 * What counts as an agent finishing (0.7.0): it was working, and it is not any more.
 *
 * The list is the core's own status, which only ever moves between `working`, `idle`, `blocked` and `done`. "Finishing"
 * is the transition out of work into a state where the program waits for nobody: `done` and `idle` and a finished
 * terminal qualify; `blocked` does not — an agent waiting for the user has not finished, it is asking.
 */
object FinishedAgents {
    /**
     * The agents that finished between [previous] and [next].
     *
     * @param previous the statuses before, by [key].
     * @param next the agents as they are now.
     * @return the agents that stopped working, in the order they appear in [next].
     */
    fun between(previous: Map<String, AgentStatus?>, next: List<MachineAgent>): List<MachineAgent> =
        next.filter { agent ->
            val terminal = agent.agent.terminal
            val was = previous[key(agent)]
            val finished = when {
                terminal.state == TerminalState.Finished -> true
                terminal.isWaitingForUser() -> false
                else -> terminal.status != AgentStatus.Working
            }
            was == AgentStatus.Working && finished
        }

    /**
     * The statuses to compare the next list against.
     *
     * @param agents the agents as they are now.
     * @return each agent's status, by [key].
     */
    fun statuses(agents: List<MachineAgent>): Map<String, AgentStatus?> =
        agents.associate { key(it) to it.agent.terminal.status }

    /** The key of an agent's row: the machine and the terminal address, which never repeat together. */
    fun key(agent: MachineAgent): String = agent.machineId + "/" + agent.agent.terminal.address
}
