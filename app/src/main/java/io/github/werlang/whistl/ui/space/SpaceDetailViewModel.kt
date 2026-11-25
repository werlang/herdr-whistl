package io.github.werlang.whistl.ui.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.LimitsRepository
import io.github.werlang.whistl.data.LimitsResult
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.PinnedAgents
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.data.UnresolvableReason
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.sortWithPinned
import io.github.werlang.whistl.data.withLiveTerminal
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.ui.agents.AgentMenu
import io.github.werlang.whistl.ui.agents.AgentNotice
import io.github.werlang.whistl.ui.agents.AgentRow
import io.github.werlang.whistl.ui.machines.MachineIssue
import io.github.werlang.whistl.ui.machines.MachineStatus
import io.github.werlang.whistl.ui.machines.classify
import io.github.werlang.whistl.ui.machines.connectionStates
import io.github.werlang.whistl.ui.spaces.CloseUi
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.RenameUi
import io.github.werlang.whistl.ui.spaces.SpaceRef
import io.github.werlang.whistl.ui.spaces.folderStateOf
import io.github.werlang.whistl.ui.spaces.moveRules
import io.github.werlang.whistl.ui.spaces.problemOf
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
 * @property machineOffline whether the machine is offline; the rows are shown without a connection.
 * @property agents the agents of the space.
 * @property newAgent the New agent button.
 * @property picker the picker, or `null` when it is closed.
 * @property opened the address of an agent that just started, to open once; cleared by [SpaceDetailViewModel.consumeOpened].
 * @property machineStatus the machine's connection status: the header says it when the machine is not connected.
 * @property machineIssue the reason, for an error.
 * @property spaceMenu the space's own overflow menu, or `null` when it is closed.
 * @property spaceRename the space's rename sheet, or `null`.
 * @property spaceClose the space's close confirmation, or `null`.
 * @property closed whether the space was closed here: the screen leaves for the previous one.
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
    val machineStatus: MachineStatus = if (machineOffline) MachineStatus.Offline else MachineStatus.Connected,
    val machineIssue: MachineIssue? = null,
    val spaceMenu: SpaceRef? = null,
    val spaceRename: RenameUi? = null,
    val spaceClose: CloseUi? = null,
    val closed: Boolean = false,
)

/** The picker's state while it is open: whether a start is running, and the last start's problem. */
private data class PickerSheet(val starting: Boolean = false, val problem: StartProblem? = null)

/** The sheets and results the Space detail holds. */
private data class DetailSheets(
    val picker: PickerSheet? = null,
    val opened: String? = null,
    val menu: AgentMenu? = null,
    val notice: AgentNotice? = null,
    /** The space's own menu, or `null` when it is closed. */
    val spaceMenu: SpaceRef? = null,
    /** The space's rename sheet, or `null`. */
    val spaceRename: RenameUi? = null,
    /** The space's close confirmation, or `null`. */
    val spaceClose: CloseUi? = null,
    /** Whether the space was closed here: the screen leaves once. */
    val closed: Boolean = false,
)

/**
 * The Space detail (SPEC §2.3, phase 4b r2): the header, the space's own overflow menu, the agents of the space, the New agent
 * button, and the picker that starts an installed agent or the machine's shell and opens it. Events in, state out.
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
    private val limits: LimitsRepository? = null,
) : ViewModel() {
    /** The pinned keys, folded into the sheet state so the state stays a five-way combine. */
    private val pinnedFlow = pins.pinned
    private val sheets = MutableStateFlow(DetailSheets())

    /** The machine's connection state, by id, for the header's status. */
    private val connections = machines.connectionStates()

    /** The machine list and their states together: the header reads the name from one and the status from the other. */
    private val machineStates = combine(machines.machines(), connections, ::Pair)

    /** The screen's state. */
    val state: StateFlow<SpaceDetailUiState> = combine(
        spaces.groups(),
        agents.agents,
        machineStates,
        installed.installed,
        combine(sheets, pinnedFlow, ::Pair),
    ) { groups, agentList, (machineList, states), installedMap, (open, pinnedKeys) ->
        build(groups, agentList.withLiveTerminal(), machineList, installedMap, states, open, pinnedKeys)
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
        machineStatus = MachineStatus.Offline,
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
        connections: Map<String, ConnectionState>,
        open: DetailSheets,
        pinnedKeys: Set<String>,
    ): SpaceDetailUiState {
        val group = groups.firstOrNull { it.machineId == machineId }
        val space = group?.spaces?.firstOrNull { it.id == spaceId }
        if (group == null || space == null) {
            return emptyState().copy(missing = true, opened = open.opened, closed = open.closed)
        }
        val machine = machineList.firstOrNull { it.id == machineId }
        val folderState = folderStateOf(group.resolutionOf(space), space.folder)
        // The space's own menu uses the same pin-section rules as the row menu, and its edits are off on an offline machine.
        val rules = moveRules(group.spaces)
        val index = group.spaces.indexOf(space)
        val ref = SpaceRef(
            machineId = machineId,
            spaceId = spaceId,
            name = space.name,
            pinned = space.pinned,
            canMoveUp = rules[index].first,
            canMoveDown = rules[index].second,
            offline = group.offline,
        )
        val (status, issue) = connections[machineId]?.let(::classify)
            ?: (if (group.offline) MachineStatus.Offline else MachineStatus.Connected) to null
        val block = when {
            group.offline -> NewAgentBlock.MachineOffline
            folderState != FolderState.Resolved -> NewAgentBlock.Folder(folderState)
            else -> null
        }
        val installedHere = installedMap[machineId].orEmpty()
        val mine = agentList.filter { it.machineId == machineId && it.spaceId == spaceId }
        val live = mine.count { it.agent.terminal.state == TerminalState.Live }
        return SpaceDetailUiState(
            missing = false,
            name = space.name,
            machineName = machine?.name ?: machineId,
            folder = space.folder,
            folderState = folderState,
            machineOffline = group.offline,
            machineStatus = status,
            machineIssue = issue,
            agents = sortWithPinned(mine, pinnedKeys).map { agent ->
                val terminal = agent.agent.terminal
                DetailAgent(
                    address = terminal.address,
                    name = agent.displayName,
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
            spaceMenu = if (open.spaceMenu != null) ref else null,
            spaceRename = open.spaceRename?.let { RenameUi(ref, it.draft, it.problem, it.machineOffline) },
            spaceClose = open.spaceClose?.let { CloseUi(ref, live) },
            closed = open.closed,
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

    /**
     * Asks this agent's machine for the subscription limits and shows them in the sheet (0.7.5). Same call as the Agents list's:
     * the sheet is one component, so the action is one.
     */
    fun showLimits() {
        val menu = sheets.value.menu ?: return
        if (menu.limitsLoading) return
        sheets.value = sheets.value.copy(menu = menu.copy(limitsLoading = true, limits = null))
        viewModelScope.launch {
            if (limits == null) {
                sheets.value = sheets.value.copy(
                    menu = sheets.value.menu?.copy(limitsLoading = false, limits = LimitsResult.Failed("no limits on this build")),
                )
                return@launch
            }
            limits.refresh(machineId)
            val answer = limits.reports().value[machineId]
            sheets.value = sheets.value.copy(menu = sheets.value.menu?.copy(limitsLoading = false, limits = answer))
        }
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

    /**
     * Opens the space's own overflow menu (SPEC §2.3). Its actions are off while the machine is offline. Nothing happens when
     * the space is gone.
     */
    fun openSpaceMenu() {
        val ref = currentSpaceRef() ?: return
        sheets.value = sheets.value.copy(spaceMenu = ref, notice = null)
    }

    /** Closes the space's overflow menu. */
    fun closeSpaceMenu() {
        sheets.value = sheets.value.copy(spaceMenu = null)
    }

    /** Pins the space, or unpins it, and closes its menu. Nothing is sent on an offline machine. */
    fun toggleSpacePin() {
        if (sheets.value.spaceMenu == null) return
        val ref = currentSpaceRef() ?: return
        if (ref.offline) return
        sheets.value = sheets.value.copy(spaceMenu = null)
        viewModelScope.launch { spaces.setPinned(machineId, spaceId, !ref.pinned) }
    }

    /** Moves the space up within its pin section, and closes its menu. Nothing moves at the edge or on an offline machine. */
    fun moveSpaceUp() = moveSpace(up = true)

    /** Moves the space down within its pin section, and closes its menu. Nothing moves at the edge or on an offline machine. */
    fun moveSpaceDown() = moveSpace(up = false)

    /** Opens the space's rename sheet, with its current name. Nothing happens on an offline machine. */
    fun askRenameSpace() {
        if (sheets.value.spaceMenu == null) return
        val ref = currentSpaceRef() ?: return
        if (ref.offline) return
        sheets.value = sheets.value.copy(spaceMenu = null, spaceRename = RenameUi(ref, ref.name, problem = null))
    }

    /** @param text the name typed. */
    fun editRenameSpace(text: String) {
        val ui = sheets.value.spaceRename ?: return
        sheets.value = sheets.value.copy(spaceRename = ui.copy(draft = text, problem = null, machineOffline = false))
    }

    /** Saves the space's new name. A rejected name shows under the field and nothing is written. */
    fun confirmRenameSpace() {
        val open = sheets.value.spaceRename ?: return
        viewModelScope.launch {
            when (val result = spaces.rename(machineId, spaceId, open.draft)) {
                is SpaceResult.Done -> sheets.value = sheets.value.copy(spaceRename = null)
                is SpaceResult.Rejected -> sheets.value = sheets.value.copy(
                    spaceRename = sheets.value.spaceRename?.copy(problem = problemOf(result.field, result.reason)),
                )
                // Offline: nothing was written. The sheet stays open, so the user can cancel, and it says why.
                SpaceResult.MachineOffline -> sheets.value = sheets.value.copy(
                    spaceRename = sheets.value.spaceRename?.copy(machineOffline = true),
                )
                is SpaceResult.UnknownSpace -> sheets.value = sheets.value.copy(spaceRename = null)
            }
        }
    }

    /** Closes the space's rename sheet without saving. */
    fun cancelRenameSpace() {
        sheets.value = sheets.value.copy(spaceRename = null)
    }

    /** Opens the close confirmation for the space, with its live agent count. Nothing happens on an offline machine. */
    fun askCloseSpace() {
        if (sheets.value.spaceMenu == null) return
        val ref = currentSpaceRef() ?: return
        if (ref.offline) return
        val live = state.value.agents.count { it.live }
        sheets.value = sheets.value.copy(spaceMenu = null, spaceClose = CloseUi(ref, live))
    }

    /** Closes the space: its live agents are stopped, then it leaves the document, and the screen leaves once closed. */
    fun confirmCloseSpace() {
        if (sheets.value.spaceClose == null) return
        sheets.value = sheets.value.copy(spaceClose = null)
        viewModelScope.launch {
            spaces.close(machineId, spaceId)
            sheets.value = sheets.value.copy(closed = true)
        }
    }

    /** Keeps the space: the confirmation closes. */
    fun cancelCloseSpace() {
        sheets.value = sheets.value.copy(spaceClose = null)
    }

    private fun moveSpace(up: Boolean) {
        if (sheets.value.spaceMenu == null) return
        val ref = currentSpaceRef() ?: return
        val allowed = if (up) ref.canMoveUp else ref.canMoveDown
        if (ref.offline || !allowed) return
        sheets.value = sheets.value.copy(spaceMenu = null)
        viewModelScope.launch {
            if (up) spaces.moveUp(machineId, spaceId) else spaces.moveDown(machineId, spaceId)
        }
    }

    /**
     * The space as the overflow menu's target, or `null` when it is gone. Its Move flags come from the same pin-section rule
     * the Spaces list uses, and its offline flag from the group, so its actions are off exactly when the row menu's are.
     */
    private fun currentSpaceRef(): SpaceRef? {
        val group = spaces.groups().value.firstOrNull { it.machineId == machineId } ?: return null
        val space = group.spaces.firstOrNull { it.id == spaceId } ?: return null
        val rules = moveRules(group.spaces)
        val index = group.spaces.indexOf(space)
        return SpaceRef(
            machineId = machineId,
            spaceId = spaceId,
            name = space.name,
            pinned = space.pinned,
            canMoveUp = rules[index].first,
            canMoveDown = rules[index].second,
            offline = group.offline,
        )
    }
}
