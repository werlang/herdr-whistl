package io.github.werlang.whistl.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.sortForAgentsScreen
import io.github.werlang.whistl.domain.AgentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The filter chips of the Agents list (SPEC §2.4). Each chip counts the agents it would show.
 */
enum class AgentsFilter {
    /** Every agent on a connected machine. */
    All,

    /** Live agents whose status is `blocked`: waiting for the user. */
    NeedsYou,

    /** Live agents whose status is `working`. */
    Working,

    /** Live agents whose status is `idle`. */
    Idle,
}

/**
 * One row of the Agents list.
 *
 * @property machineId the machine the agent runs on.
 * @property address the terminal address, shown in monospace.
 * @property name the agent's name: the name hc has, or the program's name.
 * @property status the status, or `null` for a plain program. The row shows it as a dot and a label.
 * @property waiting whether the agent is waiting for the user (live and blocked).
 * @property machineName the machine's name.
 * @property spaceName the space's name, or `null` for "No space".
 */
data class AgentRow(
    val machineId: String,
    val address: String,
    val name: String,
    val status: AgentStatus?,
    val waiting: Boolean,
    val machineName: String,
    val spaceName: String?,
)

/**
 * A filter chip.
 *
 * @property filter the filter the chip selects.
 * @property count how many agents it shows.
 * @property selected whether it is the active filter.
 */
data class FilterChip(
    val filter: AgentsFilter,
    val count: Int,
    val selected: Boolean,
)

/**
 * What the Agents list shows.
 *
 * @property chips the four filter chips, in order.
 * @property rows the rows of the active filter, in the screen's order (SPEC §2.4).
 * @property blockedCount the Agents badge: the agents waiting for the user.
 */
data class AgentsUiState(
    val chips: List<FilterChip>,
    val rows: List<AgentRow>,
    val blockedCount: Int,
)

/**
 * The Agents list (SPEC §2.4): every agent on a connected machine, filtered by a chip and sorted for the screen. Events in
 * ([select]), state out ([state]).
 *
 * @param agents the agents, and the blocked count.
 * @param machines the machines, for their names.
 */
class AgentsViewModel(
    agents: AgentsRepository,
    machines: MachinesRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(AgentsFilter.All)

    /** The list's state. */
    val state: StateFlow<AgentsUiState> = combine(
        agents.agents,
        machines.machines(),
        agents.blockedCount,
        filter,
    ) { list, machineList, blocked, selected ->
        AgentsUiState(
            chips = AgentsFilter.entries.map { chip ->
                FilterChip(filter = chip, count = list.count { chip.matches(it) }, selected = chip == selected)
            },
            rows = sortForAgentsScreen(list.filter { selected.matches(it) }).map { it.toRow(machineList) },
            blockedCount = blocked,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentsUiState(emptyList(), emptyList(), 0))

    /**
     * Selects a filter chip.
     *
     * @param chip the chip tapped.
     */
    fun select(chip: AgentsFilter) {
        filter.value = chip
    }

    private fun AgentsFilter.matches(agent: MachineAgent): Boolean = when (this) {
        AgentsFilter.All -> true
        AgentsFilter.NeedsYou -> agent.agent.terminal.isWaitingForUser()
        AgentsFilter.Working -> agent.agent.terminal.status == AgentStatus.Working
        AgentsFilter.Idle -> agent.agent.terminal.status == AgentStatus.Idle
    }

    private fun MachineAgent.toRow(machineList: List<io.github.werlang.whistl.domain.Machine>): AgentRow {
        val terminal = agent.terminal
        return AgentRow(
            machineId = machineId,
            address = terminal.address,
            name = displayName(terminal.name, terminal.argv, terminal.address),
            status = terminal.status,
            waiting = terminal.isWaitingForUser(),
            machineName = machineList.firstOrNull { it.id == machineId }?.name ?: machineId,
            spaceName = space?.name,
        )
    }
}

/**
 * The name the screens show for a terminal: the name hc has, else the program's file name, else the address.
 *
 * @param name the name given at run time, if any.
 * @param argv the program and its arguments.
 * @param address the terminal address.
 * @return the name to show.
 */
internal fun displayName(name: String?, argv: List<String>, address: String): String =
    name?.takeIf { it.isNotBlank() } ?: argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: address
