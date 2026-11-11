package io.github.werlang.whistl.ui.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.PinnedAgents
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.data.UnresolvableReason
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.sortWithPinned
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.ui.agents.AgentMenu
import io.github.werlang.whistl.ui.agents.AgentNotice
import io.github.werlang.whistl.ui.agents.AgentRow
import io.github.werlang.whistl.ui.agents.displayName
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.folderStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One agent of a space, as a row of the Space detail (SPEC §2.3): its name, its address, and its status, which the row shows on
 * its own line.
 *
 * @property address the terminal's address; tapping the row opens the Agent screen for it.
 * @property name the name the Agents list shows.
 * @property status the agent's status, or `null` for a plain program.
 * @property live whether the terminal is live.
 * @property waiting whether it waits for the user.
 */
data class DetailAgent(
    val address: String,
    val name: String,
    val status: AgentStatus?,
    val live: Boolean,
    val waiting: Boolean,
    val pinned: Boolean = false,
)

/** Why New agent is off (SPEC §2.3): the machine is offline, or the folder is not resolved on this connection. */
sealed interface NewAgentBlock {
    /** The machine is not connected. */
    data object MachineOffline : NewAgentBlock

    /**
     * The folder is not resolved: the state says whether it was never checked here, or failed with a reason.
     *
     * @property state the folder's state.
     */
    data class Folder(val state: FolderState) : NewAgentBlock
}

/**
 * The New agent button.
 *
 * @property enabled whether it can be tapped: the machine is connected and the folder is resolved.
 * @property block why it is off, or `null` when it is on.
 */
data class NewAgentUi(val enabled: Boolean, val block: NewAgentBlock?)

/** Why a start did not happen, for the picker to show in words. */
sealed interface StartProblem {
    /** The machine could not start the program. */
    data object CouldNotStart : StartProblem

    /** The machine is not connected, or the connection dropped. */
    data object MachineOffline : StartProblem

    /** The space is no longer on the machine. */
    data object UnknownSpace : StartProblem

    /**
     * The folder is not available on the machine.
     *
     * @property reason why, or `null` when the folder has not been checked on this device yet.
     */
    data class FolderUnavailable(val reason: UnresolvableReason?) : StartProblem
}

/**
 * The picker sheet: picker only, no text box (SPEC §2.3). Its choices are the agents installed on the machine, then Terminal.
 *
 * @property choices the choices, in order: each installed agent, then the machine's shell.
 * @property starting whether a start is in progress.
 * @property problem why the last start did not happen, or `null`.
 */
data class PickerUi(val choices: List<StartChoice>, val starting: Boolean, val problem: StartProblem?)

/**
 * What the Space detail shows (SPEC §2.3).
 *
 * @property missing `true` when the space is no longer on its machine; the screen says so and offers Back.
 * @property name the space's name.
 * @property machineName the machine's name.
 * @property folder the folder as stored.
 * @property folderState the folder's state.
 * @property machineOffline whether the machine is offline; the header says so and the rows are shown without a connection.
 * @property agents the agents of the space.
 * @property newAgent the New agent button.
 * @property picker the picker, or `null` when it is closed.
 * @property opened the address of an agent that just started, to open once; cleared by [SpaceDetailViewModel.consumeOpened].
 */
data class SpaceDetailUiState(
    val missing: Boolean,
    val name: String,
    val machineName: String,
    val folder: String,
    val folderState: FolderState,
    val machineOffline: Boolean,
    val agents: List<DetailAgent>,
    val newAgent: NewAgentUi,
    val picker: PickerUi?,
    val opened: String?,
    val menu: AgentMenu? = null,
    val notice: AgentNotice? = null,
)

/** The picker's state while it is open: whether a start is running, and the last start's problem. */
private data class PickerSheet(val starting: Boolean = false, val problem: StartProblem? = null)

/** The sheets and results the Space detail holds. */
private data class DetailSheets(
    val picker: PickerSheet? = null,
    val opened: String? = null,
    val menu: AgentMenu? = null,
    val notice: AgentNotice? = null,
)

/**
 * The Space detail (SPEC §2.3, phase 4b r2): the header, the agents of the space, the New agent button, and the picker that
 * starts an installed agent or the machine's shell and opens it. Events in, state out.
 *
 * @param machineId the machine the space lives on.
 * @param spaceId the space.
 * @param spaces the spaces, for the header and the folder state.
 * @param agents the agents, for the rows, and the start.
 * @param machines the added machines, for the machine's name.
 * @param installed the agents installed on each machine, for the picker.
 */
class SpaceDetailViewModel(
    private val machineId: String,
    private val spaceId: String,
    private val spaces: SpacesRepository,
    private val agents: AgentsRepository,
    private val machines: MachinesRepository,
    private val installed: InstalledAgentsRepository,
    private val pins: PinnedAgents,
) : ViewModel() {
    /** The pinned keys, folded into the sheet state so the state stays a five-way combine. */
    private val pinnedFlow = pins.pinned
    private val sheets = MutableStateFlow(DetailSheets())

    /** The screen's state. */
    val state: StateFlow<SpaceDetailUiState> = combine(
        spaces.groups(),
        agents.agents,
        machines.machines(),
        installed.installed,
        combine(sheets, pinnedFlow, ::Pair),
    ) { groups, agentList, machineList, installedMap, (open, pinnedKeys) ->
        build(groups, agentList, machineList, installedMap, open, pinnedKeys)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyState())

    /**
     * Opens the picker. Nothing happens while New agent is off: the picker is only for a connected machine and a resolved folder.
     */
    fun openPicker() {
        if (!state.value.newAgent.enabled) return
        sheets.value = sheets.value.copy(picker = PickerSheet())
    }

    /** Closes the picker. */
    fun closePicker() {
        sheets.value = sheets.value.copy(picker = null)
    }

    /**
     * Starts the chosen agent, or the machine's shell, in this space. On success the new agent is opened once; a failure shows
     * its typed problem in the picker.
     *
     * @param choice the agent or the shell.
     */
    fun start(choice: StartChoice) {
        sheets.value = sheets.value.copy(picker = PickerSheet(starting = true))
        viewModelScope.launch {
            when (val result = agents.start(machineId, spaceId, choice)) {
                is StartResult.Started -> sheets.value = DetailSheets(picker = null, opened = result.address)
                is StartResult.FolderUnavailable -> fail(StartProblem.FolderUnavailable(result.reason))
                StartResult.UnknownSpace -> fail(StartProblem.UnknownSpace)
                StartResult.CouldNotStart -> fail(StartProblem.CouldNotStart)
                StartResult.Offline -> fail(StartProblem.MachineOffline)
            }
        }
    }

    /** The agent that just started has been opened: nothing is pending. */
    fun consumeOpened() {
        sheets.value = sheets.value.copy(opened = null)
    }

    private fun fail(problem: StartProblem) {
        sheets.value = sheets.value.copy(picker = PickerSheet(starting = false, problem = problem))
    }

    private fun emptyState(): SpaceDetailUiState = SpaceDetailUiState(
        missing = false,
        name = "",
        machineName = "",
        folder = "",
        folderState = FolderState.NotChecked,
        machineOffline = true,
        agents = emptyList(),
        newAgent = NewAgentUi(enabled = false, block = NewAgentBlock.MachineOffline),
        picker = null,
        opened = null,
    )

    private fun build(
        groups: List<MachineSpaces>,
        agentList: List<MachineAgent>,
        machineList: List<Machine>,
        installedMap: Map<String, List<io.github.werlang.whistl.domain.KnownAgent>>,
        open: DetailSheets,
        pinnedKeys: Set<String>,
    ): SpaceDetailUiState {
        val group = groups.firstOrNull { it.machineId == machineId }
        val space = group?.spaces?.firstOrNull { it.id == spaceId }
        if (group == null || space == null) {
            return emptyState().copy(missing = true, opened = open.opened)
        }
        val machine = machineList.firstOrNull { it.id == machineId }
        val folderState = folderStateOf(group.resolutionOf(space))
        val block = when {
            group.offline -> NewAgentBlock.MachineOffline
            folderState != FolderState.Resolved -> NewAgentBlock.Folder(folderState)
            else -> null
        }
        val installedHere = installedMap[machineId].orEmpty()
        return SpaceDetailUiState(
            missing = false,
            name = space.name,
            machineName = machine?.name ?: machineId,
            folder = space.folder,
            folderState = folderState,
            machineOffline = group.offline,
            agents = sortWithPinned(
                agentList.filter { it.machineId == machineId && it.spaceId == spaceId },
                pinnedKeys,
            ).map { agent ->
                val terminal = agent.agent.terminal
                DetailAgent(
                    address = terminal.address,
                    name = displayName(terminal.name, terminal.argv, terminal.address, terminal.labels),
                    status = terminal.status,
                    live = terminal.state == TerminalState.Live,
                    waiting = terminal.isWaitingForUser(),
                    pinned = PinnedAgents.key(machineId, terminal.address) in pinnedKeys,
                )
            },
            newAgent = NewAgentUi(enabled = block == null, block = block),
            picker = open.picker?.let { sheet ->
                PickerUi(
                    choices = installedHere.map { StartChoice.Known(it) } + StartChoice.Terminal,
                    starting = sheet.starting,
                    problem = sheet.problem,
                )
            },
            opened = open.opened,
            menu = open.menu,
            notice = open.notice,
        )
    }

    /**
     * Opens the action sheet of one of this space's agents, with the row built as the Agents list builds it, so the same
     * sheet serves both screens (SPEC §2.4).
     *
     * @param agent the row tapped.
     */
    fun openMenu(agent: DetailAgent) {
        val current = state.value
        val row = AgentRow(
            machineId = machineId,
            address = agent.address,
            name = agent.name,
            status = agent.status,
            waiting = agent.waiting,
            live = agent.live,
            machineName = current.machineName,
            spaceName = current.name,
            pinned = agent.pinned,
        )
        sheets.value = sheets.value.copy(menu = AgentMenu(row), notice = null)
    }

    /** Closes the action sheet. */
    fun closeMenu() {
        sheets.value = sheets.value.copy(menu = null)
    }

    /** @param text what the user typed as the new name. */
    fun renameDraft(text: String) {
        sheets.value = sheets.value.copy(menu = sheets.value.menu?.copy(draft = text))
    }

    /** Turns the sheet into the rename field, with the current name in it. */
    fun startRename() {
        val menu = sheets.value.menu ?: return
        sheets.value = sheets.value.copy(menu = menu.copy(renaming = true, draft = menu.row.name))
    }

    /** Saves the typed name. An empty name is refused rather than sent. */
    fun confirmRename() {
        val menu = sheets.value.menu ?: return
        val name = menu.draft.trim()
        if (name.isEmpty()) return
        sheets.value = sheets.value.copy(menu = null)
        act(menu.row, AgentAction.Rename(name), AgentNotice.Renamed)
    }

    /** Asks the agent to stop what it is doing. */
    fun interrupt() = actFromSheet(AgentAction.Interrupt, AgentNotice.Interrupted)

    /** Turns the sheet into the question closing asks. */
    fun askClose() {
        sheets.value = sheets.value.copy(menu = sheets.value.menu?.copy(confirmingClose = true))
    }

    /** Goes back from the close question. */
    fun cancelClose() {
        sheets.value = sheets.value.copy(menu = sheets.value.menu?.copy(confirmingClose = false))
    }

    /** Stops the agent and removes it. */
    fun close() = actFromSheet(AgentAction.Close, AgentNotice.Closed)

    /** Pins the sheet's agent, or unpins it. Nothing is sent to the core. */
    fun togglePin() {
        val row = sheets.value.menu?.row ?: return
        val key = PinnedAgents.key(row.machineId, row.address)
        pins.toggle(key)
        val notice = if (key in pins.pinned.value) AgentNotice.Pinned else AgentNotice.Unpinned
        sheets.value = sheets.value.copy(menu = null, notice = notice)
    }

    /** Hides the message the last action left. */
    fun dismissNotice() {
        sheets.value = sheets.value.copy(notice = null)
    }

    private fun actFromSheet(action: AgentAction, done: AgentNotice) {
        val row = sheets.value.menu?.row ?: return
        sheets.value = sheets.value.copy(menu = null)
        act(row, action, done)
    }

    private fun act(row: AgentRow, action: AgentAction, done: AgentNotice) {
        viewModelScope.launch {
            val notice = when (agents.act(row.machineId, row.address, action)) {
                ActionResult.Done -> done
                ActionResult.Offline -> AgentNotice.Offline
                ActionResult.Gone -> AgentNotice.Gone
                is ActionResult.Failed -> AgentNotice.Failed
            }
            sheets.value = sheets.value.copy(notice = notice)
        }
    }
}
