package io.github.werlang.whistl.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.folderStateOf
import io.github.werlang.whistl.ui.space.NewAgentBlock
import io.github.werlang.whistl.ui.space.NewAgentUi
import io.github.werlang.whistl.ui.space.PickerUi
import io.github.werlang.whistl.ui.space.StartProblem
import io.github.werlang.whistl.data.PinnedAgents
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.sortWithPinned
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
 * The space the Agents list is filtered to (0.6.4): tapping a space on the Spaces screen lands here, the same list with
 * one space's agents, instead of a screen of its own.
 *
 * @property machineId the machine the space lives on.
 * @property spaceId the space.
 */
data class SpaceFilter(val machineId: String, val spaceId: String)

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

    /** Terminals no space claims: started outside the app, or left behind by a removed space (0.6.3). */
    NoSpace,
}

/** Whether a space claims this agent: the label the app writes, or a space record that still resolves (0.6.3). */
private val MachineAgent.isBound: Boolean
    get() = space != null || agent.spaceId != null

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
    val pinned: Boolean = false,
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
    /** The space the list is filtered to, by name, or `null` when it shows every space (0.6.4). */
    val filterName: String? = null,
    /** New agent, offered only while the list shows one space (0.6.5). */
    val newAgent: NewAgentUi? = null,
    /** The picker, or `null` when it is closed. */
    val picker: PickerUi? = null,
    /** The address of an agent that just started, to open once; cleared by [AgentsViewModel.consumeOpened]. */
    val opened: String? = null,
    /** The machine's name, for the picker's title. */
    val pickerMachineName: String = "",
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
    val confirmingClose: Boolean = false,
)

/** What the list tells the user after an action (phase 9, SPEC §2.4). */
enum class AgentNotice {
    /** The agent was interrupted. */
    Interrupted,

    /** The agent was closed and removed. */
    Closed,

    /** The agent was pinned. */
    Pinned,

    /** The agent was unpinned. */
    Unpinned,

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
    private val machines: MachinesRepository,
    private val pins: PinnedAgents,
    private val spaces: SpacesRepository,
    private val installed: InstalledAgentsRepository,
    private val spaceFilter: MutableStateFlow<SpaceFilter?> = MutableStateFlow(null),
) : ViewModel() {
    private val filter = MutableStateFlow(AgentsFilter.All)
    private val open = MutableStateFlow<AgentMenu?>(null)
    private val notice = MutableStateFlow<AgentNotice?>(null)

    /** The chip and the space the list is limited to, as one flow: `combine` has a typed overload for five flows, not six. */
    private val selection: Flow<Pair<AgentsFilter, SpaceFilter?>> = combine(filter, spaceFilter, ::Pair)

    /** The picker's state while it is open: whether a start is running, and the last start's problem. */
    private data class PickerSheet(val starting: Boolean = false, val problem: StartProblem? = null)

    /** Everything the list holds besides its rows: the sheets, the pinned keys and the start in flight. */
    private data class Sheets(
        val menu: AgentMenu? = null,
        val notice: AgentNotice? = null,
        val pinned: Set<String> = emptySet(),
        val picker: PickerSheet? = null,
        val opened: String? = null,
    )

    private val picker = MutableStateFlow<PickerSheet?>(null)
    private val opened = MutableStateFlow<String?>(null)

    /** The sheet, the last notice and the pinned keys, as one flow so the list's state stays a five-way combine. */
    private val sheet: Flow<Sheets> = combine(open, notice, pins.pinned, picker, opened, ::Sheets)

    /** The list's state. */
    val state: StateFlow<AgentsUiState> = combine(
        agents.agents,
        machines.machines(),
        agents.blockedCount,
        selection,
        combine(sheet, spaces.groups(), installed.installed, ::Triple),
    ) { all, machineList, blocked, (selected, only), (sheetsOf, groups, installedMap) ->
        val (menu, lastNotice, pinnedKeys, pickerSheet, pendingOpened) = sheetsOf
        // One space's agents, when the list was opened from that space (0.6.4), else every agent a space holds. Unbound
        // terminals sit behind their own chip: they used to drown the unfiltered list (0.6.2), and hiding them completely
        // left them unreachable (0.6.3).
        val list = if (only == null) {
            all
        } else {
            all.filter { it.machineId == only.machineId && it.space?.id == only.spaceId }
        }
        // New agent belongs to a space: it is offered only while the list shows one, and only when its folder resolves
        // here on a connected machine (0.6.5).
        val group = only?.let { filter -> groups.firstOrNull { it.machineId == filter.machineId } }
        val space = only?.let { filter -> group?.spaces?.firstOrNull { it.id == filter.spaceId } }
        val folderState = if (space == null || group == null) null else folderStateOf(group.resolutionOf(space))
        val block = when {
            only == null -> null
            group == null || group.offline -> NewAgentBlock.MachineOffline
            folderState != FolderState.Resolved -> NewAgentBlock.Folder(folderState ?: FolderState.NotChecked)
            else -> null
        }
        val machineName = machineList.firstOrNull { it.id == only?.machineId }?.name.orEmpty()
        AgentsUiState(
            chips = AgentsFilter.entries.map { chip ->
                FilterChip(filter = chip, count = list.count { chip.matches(it) }, selected = chip == selected)
            },
            rows = sortWithPinned(list.filter { selected.matches(it) }, pinnedKeys).map { it.toRow(machineList, pinnedKeys) },
            blockedCount = blocked,
            menu = menu,
            notice = lastNotice,
            filterName = only?.let { filter -> list.firstOrNull()?.space?.name },
            newAgent = space?.let { NewAgentUi(enabled = block == null, block = block) },
            picker = pickerSheet?.let { sheet ->
                PickerUi(
                    choices = installedMap[only?.machineId].orEmpty().map { StartChoice.Known(it) } + StartChoice.Terminal,
                    starting = sheet.starting,
                    problem = sheet.problem,
                )
            },
            opened = pendingOpened,
            pickerMachineName = machineName,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentsUiState(emptyList(), emptyList(), 0))

    /**
     * Opens the picker: what can be started in the space the list shows. Nothing happens while New agent is off, or when
     * the list shows every space.
     */
    fun openPicker() {
        val only = spaceFilter.value ?: return
        if (state.value.newAgent?.enabled != true) return
        picker.value = PickerSheet()
    }

    /** Closes the picker. */
    fun closePicker() {
        picker.value = null
    }

    /**
     * Starts the chosen agent, or the machine's shell, in the filtered space. On success the new agent is opened once; a
     * failure shows its typed problem in the picker.
     *
     * @param choice the agent or the shell.
     */
    fun start(choice: StartChoice) {
        val only = spaceFilter.value ?: return
        picker.value = PickerSheet(starting = true)
        viewModelScope.launch {
            when (val result = agents.start(only.machineId, only.spaceId, choice)) {
                is StartResult.Started -> {
                    picker.value = null
                    opened.value = result.address
                }
                is StartResult.FolderUnavailable -> fail(StartProblem.FolderUnavailable(result.reason))
                StartResult.UnknownSpace -> fail(StartProblem.UnknownSpace)
                StartResult.CouldNotStart -> fail(StartProblem.CouldNotStart)
                StartResult.Offline -> fail(StartProblem.MachineOffline)
            }
        }
    }

    /** The agent that just started has been opened: nothing is pending. */
    fun consumeOpened() {
        opened.value = null
    }

    private fun fail(problem: StartProblem) {
        picker.value = PickerSheet(starting = false, problem = problem)
    }

    /** Drops the space filter: the list goes back to every space (0.6.4). */
    fun clearSpaceFilter() {
        spaceFilter.value = null
    }

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

    /** Turns the sheet into the question closing asks: removing an agent cannot be undone. */
    fun askClose() {
        open.value = open.value?.copy(confirmingClose = true)
    }

    /** Goes back from the close question to the actions. */
    fun cancelClose() {
        open.value = open.value?.copy(confirmingClose = false)
    }

    /** Stops the agent and removes it from the list. */
    fun close() = act(AgentAction.Close, AgentNotice.Closed)

    /** Pins the sheet's agent, or unpins it. A pin is local: nothing is sent to the core. */
    fun togglePin() {
        val row = open.value?.row ?: return
        val key = PinnedAgents.key(row.machineId, row.address)
        pins.toggle(key)
        open.value = null
        notice.value = if (key in pins.pinned.value) AgentNotice.Pinned else AgentNotice.Unpinned
    }

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
        // `All` is every agent a space holds; the unbound terminals have their own chip (0.6.3).
        AgentsFilter.All -> agent.isBound
        AgentsFilter.NeedsYou -> agent.isBound && agent.agent.terminal.isWaitingForUser()
        AgentsFilter.Working -> agent.isBound && agent.agent.terminal.status == AgentStatus.Working
        AgentsFilter.Idle -> agent.isBound && agent.agent.terminal.status == AgentStatus.Idle
        AgentsFilter.NoSpace -> !agent.isBound
    }

    private fun MachineAgent.toRow(
        machineList: List<io.github.werlang.whistl.domain.Machine>,
        pinnedKeys: Set<String>,
    ): AgentRow {
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
            pinned = PinnedAgents.key(machineId, terminal.address) in pinnedKeys,
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
