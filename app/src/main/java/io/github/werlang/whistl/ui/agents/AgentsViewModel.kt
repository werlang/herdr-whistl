package io.github.werlang.whistl.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.sortForAgentsScreen
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val live: Boolean,
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
    val menu: AgentMenu? = null,
    val notice: AgentNotice? = null,
)

/**
 * The action sheet of one row (phase 9, SPEC §2.4).
 *
 * @property row the agent the sheet acts on.
 * @property renaming whether the sheet is asking for a new name.
 * @property draft what the user has typed so far.
 */
data class AgentMenu(
    val row: AgentRow,
    val renaming: Boolean = false,
    val draft: String = "",
)

/** What the list tells the user after an action (phase 9, SPEC §2.4). */
enum class AgentNotice {
    /** The agent was interrupted. */
    Interrupted,

    /** The agent was stopped. */
    Stopped,

    /** The agent was stopped and removed. */
    Closed,

    /** The agent's new name was saved. */
    Renamed,

    /** Nothing was sent: the machine is not connected. */
    Offline,

    /** The agent is gone already. */
    Gone,

    /** The core refused the action. */
    Failed,
}

/**
 * The Agents list (SPEC §2.4): every agent on a connected machine, filtered by a chip and sorted for the screen. Events in
 * ([select]), state out ([state]).
 *
 * @param agents the agents, and the blocked count.
 * @param machines the machines, for their names.
 */
class AgentsViewModel(
    private val agents: AgentsRepository,
    machines: MachinesRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(AgentsFilter.All)
    private val open = MutableStateFlow<AgentMenu?>(null)
    private val notice = MutableStateFlow<AgentNotice?>(null)

    /** The sheet and the last notice, as one flow so the list's state stays a five-way combine. */
    private val sheet: Flow<Pair<AgentMenu?, AgentNotice?>> = combine(open, notice, ::Pair)

    /** The list's state. */
    val state: StateFlow<AgentsUiState> = combine(
        agents.agents,
        machines.machines(),
        agents.blockedCount,
        filter,
        sheet,
    ) { list, machineList, blocked, selected, (menu, lastNotice) ->
        AgentsUiState(
            chips = AgentsFilter.entries.map { chip ->
                FilterChip(filter = chip, count = list.count { chip.matches(it) }, selected = chip == selected)
            },
            rows = sortForAgentsScreen(list.filter { selected.matches(it) }).map { it.toRow(machineList) },
            blockedCount = blocked,
            menu = menu,
            notice = lastNotice,
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

    /**
     * Opens the action sheet of a row (phase 9, SPEC §2.4).
     *
     * @param row the agent the sheet acts on.
     */
    fun openMenu(row: AgentRow) {
        notice.value = null
        open.value = AgentMenu(row)
    }

    /** Closes the action sheet. */
    fun closeMenu() {
        open.value = null
    }

    /** Turns the sheet into a rename field, with the current name already in it. */
    fun startRename() {
        val menu = open.value ?: return
        open.value = menu.copy(renaming = true, draft = menu.row.name)
    }

    /** @param text what the user has typed so far. */
    fun renameDraft(text: String) {
        open.value = open.value?.copy(draft = text)
    }

    /** Saves the typed name. An empty name is refused here rather than sent to the core. */
    fun confirmRename() {
        val menu = open.value ?: return
        val name = menu.draft.trim()
        if (name.isEmpty()) return
        open.value = null
        run(menu.row, AgentAction.Rename(name), AgentNotice.Renamed)
    }

    /** Asks the agent to stop what it is doing. */
    fun interrupt() = act(AgentAction.Interrupt, AgentNotice.Interrupted)

    /** Ends the agent's process. */
    fun stop() = act(AgentAction.Stop, AgentNotice.Stopped)

    /** Stops the agent and removes it from the list. */
    fun close() = act(AgentAction.Close, AgentNotice.Closed)

    /** Hides the message the last action left. */
    fun dismissNotice() {
        notice.value = null
    }

    private fun act(action: AgentAction, done: AgentNotice) {
        val row = open.value?.row ?: return
        open.value = null
        run(row, action, done)
    }

    private fun run(row: AgentRow, action: AgentAction, done: AgentNotice) {
        viewModelScope.launch {
            notice.value = when (agents.act(row.machineId, row.address, action)) {
                ActionResult.Done -> done
                ActionResult.Offline -> AgentNotice.Offline
                ActionResult.Gone -> AgentNotice.Gone
                is ActionResult.Failed -> AgentNotice.Failed
            }
        }
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
            name = displayName(terminal.name, terminal.argv, terminal.address, terminal.labels),
            status = terminal.status,
            waiting = terminal.isWaitingForUser(),
            live = terminal.state == TerminalState.Live,
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
internal fun displayName(
    name: String?,
    argv: List<String>,
    address: String,
    labels: Map<String, String> = emptyMap(),
): String = labels[Agent.NAME_LABEL]?.takeIf { it.isNotBlank() }
    ?: name?.takeIf { it.isNotBlank() }
    ?: argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    ?: address
