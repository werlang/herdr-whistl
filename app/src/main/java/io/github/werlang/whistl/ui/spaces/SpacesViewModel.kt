package io.github.werlang.whistl.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.DEFAULT_FOLDER
import io.github.werlang.whistl.data.FolderResolution
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.SpaceField
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.UnresolvableReason
import io.github.werlang.whistl.data.defaultSpaceName
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.ui.machines.machineAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How a space's folder reads on its row (phase 4 decision 1): checked on this connection, not checked on this device yet, or
 * unavailable with the typed reason. A row never says "No space" for a folder it could not check.
 */
sealed interface FolderState {
    /** Checked on the live connection: the folder is a directory hc can enter. */
    data object Resolved : FolderState

    /** Not checked on this device yet: the machine has not connected with this folder. */
    data object NotChecked : FolderState

    /**
     * The check failed.
     *
     * @property reason why.
     */
    data class Unavailable(val reason: UnresolvableReason) : FolderState
}

/**
 * The folder state of a space, from its resolution in the repository.
 *
 * @param resolution the resolution, or `null` when the folder has not been checked on this device.
 * @return the state.
 */
fun folderStateOf(resolution: FolderResolution?): FolderState = when (resolution) {
    null -> FolderState.NotChecked
    is FolderResolution.Resolved -> FolderState.Resolved
    is FolderResolution.Unresolvable -> FolderState.Unavailable(resolution.reason)
}

/**
 * One agent of a space, as a dot on its row: its status (the row also names the status in words, so the dot is never the only
 * signal), whether it is live, and whether it waits for the user.
 *
 * @property status the agent's status, or `null` for a plain program.
 * @property live whether the terminal is live.
 * @property waiting whether it waits for the user (`isWaitingForUser`).
 */
data class AgentDot(val status: AgentStatus?, val live: Boolean, val waiting: Boolean)

/**
 * A space row (SPEC §2.2).
 *
 * @property machineId the machine.
 * @property spaceId the space.
 * @property name the space's name.
 * @property folder the folder as stored.
 * @property pinned whether the space is pinned.
 * @property agents one dot per agent of the space.
 * @property waitingCount how many of those agents wait for the user: the waiting badge when it is above zero.
 * @property folderState the folder's state.
 * @property canMoveUp whether Move up can run: the row above is in the same pin section of the same machine.
 * @property canMoveDown whether Move down can run: the row below is in the same pin section of the same machine.
 * @property dimmed whether the row is dimmed: its machine is offline.
 */
data class SpaceRow(
    val machineId: String,
    val spaceId: String,
    val name: String,
    val folder: String,
    val pinned: Boolean,
    val agents: List<AgentDot>,
    val waitingCount: Int,
    val folderState: FolderState,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val dimmed: Boolean,
)

/**
 * One machine's group of rows, in the order the repository gives them: pinned first, then the user's order.
 *
 * @property machineId the machine.
 * @property name the machine's name.
 * @property address the machine's `user@host[:port]`.
 * @property offline whether the machine is offline: the group's subtitle says so, and its rows are dimmed.
 * @property rows the rows.
 */
data class SpaceGroup(
    val machineId: String,
    val name: String,
    val address: String,
    val offline: Boolean,
    val rows: List<SpaceRow>,
)

/**
 * A machine a new space can be created on.
 *
 * @property id the machine.
 * @property name its name.
 * @property address its `user@host[:port]`.
 * @property offline whether it is offline.
 */
data class MachineChoice(val id: String, val name: String, val address: String, val offline: Boolean)

/** A typed problem of one field of a space form. The screen maps each to its message. */
enum class SpaceProblem {
    /** The folder is empty. */
    FolderEmpty,

    /** The folder has a NUL or a line break. */
    FolderBad,

    /** The name is empty. */
    NameEmpty,

    /** The name is longer than the limit. */
    NameTooLong,
}

/**
 * The New space sheet (SPEC §2.2).
 *
 * @property machines the machines a space can be created on, in the order the user added them.
 * @property selectedId the machine the space goes to: the one the user picked, else the first machine.
 * @property folder the folder as typed.
 * @property name the name as typed.
 * @property defaultName the name a blank name takes: the folder's last segment, shown as the placeholder.
 * @property folderProblem the folder's problem, after the last Create.
 * @property nameProblem the name's problem, after the last Create.
 * @property machineOffline whether the last Create found the machine offline.
 */
data class NewSpaceUi(
    val machines: List<MachineChoice>,
    val selectedId: String?,
    val folder: String,
    val name: String,
    val defaultName: String,
    val folderProblem: SpaceProblem?,
    val nameProblem: SpaceProblem?,
    val machineOffline: Boolean,
)

/**
 * A space a sheet is about.
 *
 * @property machineId the machine.
 * @property spaceId the space.
 * @property name its name.
 * @property pinned whether it is pinned.
 * @property canMoveUp whether Move up can run.
 * @property canMoveDown whether Move down can run.
 * @property offline whether its machine is offline: its edits cannot be written, so its menu actions are off.
 */
data class SpaceRef(
    val machineId: String,
    val spaceId: String,
    val name: String,
    val pinned: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val offline: Boolean,
)

/**
 * The rename sheet.
 *
 * @property target the space.
 * @property draft the name as typed.
 * @property problem the name's problem, after the last Save.
 */
data class RenameUi(val target: SpaceRef, val draft: String, val problem: SpaceProblem?)

/**
 * The close confirmation.
 *
 * @property target the space.
 * @property agentCount how many live agents it has, which the confirmation says will be stopped.
 */
data class CloseUi(val target: SpaceRef, val agentCount: Int)

/**
 * What the Spaces screen shows.
 *
 * @property groups one group per machine, in machine order.
 * @property hasMachines whether any machine is added.
 * @property isEmpty whether no machine holds a space.
 * @property newSpace the New space sheet, or `null` when it is closed.
 * @property menu the row menu's space, or `null`.
 * @property rename the rename sheet, or `null`.
 * @property close the close confirmation, or `null`.
 */
data class SpacesUiState(
    val groups: List<SpaceGroup>,
    val hasMachines: Boolean,
    val isEmpty: Boolean,
    val newSpace: NewSpaceUi?,
    val menu: SpaceRef?,
    val rename: RenameUi?,
    val close: CloseUi?,
)

/**
 * Whether each row of a machine can move up and down: a neighbour counts only when it is in the same pin section, so a move
 * never crosses the pinned boundary (the rule of the repository's `moved()`).
 *
 * @param rows the machine's spaces, in display order.
 * @return for each row, whether it can move up and whether it can move down.
 */
fun moveRules(rows: List<SpaceRecord>): List<Pair<Boolean, Boolean>> = rows.mapIndexed { index, row ->
    val up = index > 0 && rows[index - 1].pinned == row.pinned
    val down = index < rows.lastIndex && rows[index + 1].pinned == row.pinned
    up to down
}

/**
 * The form problem a repository rejection stands for.
 *
 * @param field the field the repository rejected.
 * @param reason why.
 * @return the problem.
 */
fun problemOf(field: SpaceField, reason: Rejection): SpaceProblem = when (field) {
    SpaceField.Folder -> if (reason == Rejection.EMPTY) SpaceProblem.FolderEmpty else SpaceProblem.FolderBad
    SpaceField.Name -> if (reason == Rejection.TOO_LONG) SpaceProblem.NameTooLong else SpaceProblem.NameEmpty
}

/** The New space sheet's draft, before it is shown: what the user has typed and the last Create's outcome. */
private data class NewDraft(
    val selectedId: String? = null,
    val folder: String = "",
    val name: String = "",
    val folderProblem: SpaceProblem? = null,
    val nameProblem: SpaceProblem? = null,
    val machineOffline: Boolean = false,
)

/** A space identified by its machine and id. */
private data class SpaceKey(val machineId: String, val spaceId: String)

/** The sheets the Spaces screen can have open, and the text in them. */
private data class Sheets(
    val newSpace: NewDraft? = null,
    val menuFor: SpaceKey? = null,
    val renameFor: SpaceKey? = null,
    val renameDraft: String = "",
    val renameProblem: SpaceProblem? = null,
    val closeFor: SpaceKey? = null,
)

/**
 * The Spaces screen (SPEC §2.2, phase 4b r2): the spaces of each machine in the repository's order, the row menu (Pin, Move
 * up, Move down, Rename, Close space), the New space sheet, and the rename and close sheets. Events in, state out.
 *
 * The order is the repository's: pinned first, then the user's order. This view model does not sort.
 *
 * @param spaces the spaces of each machine, and their edits.
 * @param agents the agents, for each row's dots and the close count.
 * @param machines the added machines, for the New space choices and each group's name.
 */
class SpacesViewModel(
    private val spaces: SpacesRepository,
    private val agents: AgentsRepository,
    private val machines: MachinesRepository,
) : ViewModel() {
    private val sheets = MutableStateFlow(Sheets())

    /** The screen's state. */
    val state: StateFlow<SpacesUiState> = combine(spaces.groups(), agents.agents, machines.machines(), sheets) {
        groups, agentList, machineList, open ->
        build(groups, agentList, machineList, open)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SpacesUiState(emptyList(), false, true, null, null, null, null))

    /** Opens the New space sheet, with the first machine as the one the space goes to. */
    fun openNewSpace() {
        sheets.value = sheets.value.copy(newSpace = NewDraft())
    }

    /** Closes the New space sheet. */
    fun closeNewSpace() {
        sheets.value = sheets.value.copy(newSpace = null)
    }

    /**
     * Picks the machine the new space goes to.
     *
     * @param id the machine.
     */
    fun pickMachine(id: String) = editNewSpace { it.copy(selectedId = id, machineOffline = false) }

    /**
     * The folder field changed.
     *
     * @param text the folder typed.
     */
    fun editFolder(text: String) = editNewSpace { it.copy(folder = text, folderProblem = null, machineOffline = false) }

    /**
     * The name field changed.
     *
     * @param text the name typed.
     */
    fun editName(text: String) = editNewSpace { it.copy(name = text, nameProblem = null) }

    /**
     * Creates the space on the chosen machine. A blank folder is `~` and a blank name is the folder's last segment, both as the
     * repository defines them. A rejection shows under its field; a machine that is offline is reported in the sheet.
     */
    fun createSpace() {
        val draft = sheets.value.newSpace ?: return
        val machineId = draft.selectedId ?: state.value.newSpace?.selectedId ?: return
        viewModelScope.launch {
            when (val result = spaces.create(machineId, draft.folder, draft.name)) {
                is SpaceResult.Done -> sheets.value = sheets.value.copy(newSpace = null)
                is SpaceResult.Rejected -> editNewSpace {
                    if (result.field == SpaceField.Folder) {
                        it.copy(folderProblem = problemOf(result.field, result.reason), nameProblem = null)
                    } else {
                        it.copy(nameProblem = problemOf(result.field, result.reason), folderProblem = null)
                    }
                }

                SpaceResult.MachineOffline -> editNewSpace { it.copy(machineOffline = true) }
                is SpaceResult.UnknownSpace -> Unit
            }
        }
    }

    /**
     * Opens the row menu of a space.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     */
    fun openMenu(machineId: String, spaceId: String) {
        sheets.value = sheets.value.copy(menuFor = SpaceKey(machineId, spaceId))
    }

    /** Closes the row menu. */
    fun closeMenu() {
        sheets.value = sheets.value.copy(menuFor = null)
    }

    /** Pins the menu's space, or unpins it, and closes the menu. */
    fun togglePin() {
        val key = sheets.value.menuFor ?: return
        val ref = refOf(key) ?: return
        if (ref.offline) return
        sheets.value = sheets.value.copy(menuFor = null)
        viewModelScope.launch { spaces.setPinned(key.machineId, key.spaceId, !ref.pinned) }
    }

    /** Moves the menu's space up within its pin section, and closes the menu. Nothing moves at the edge. */
    fun moveUp() = move(up = true)

    /** Moves the menu's space down within its pin section, and closes the menu. Nothing moves at the edge. */
    fun moveDown() = move(up = false)

    /** Opens the rename sheet for the menu's space, with its current name. */
    fun askRename() {
        val key = sheets.value.menuFor ?: return
        val ref = refOf(key) ?: return
        if (ref.offline) return
        sheets.value = sheets.value.copy(menuFor = null, renameFor = key, renameDraft = ref.name, renameProblem = null)
    }

    /**
     * The rename field changed.
     *
     * @param text the name typed.
     */
    fun editRename(text: String) {
        sheets.value = sheets.value.copy(renameDraft = text, renameProblem = null)
    }

    /** Saves the new name. A rejected name shows under the field and nothing is written. */
    fun confirmRename() {
        val open = sheets.value
        val key = open.renameFor ?: return
        viewModelScope.launch {
            when (val result = spaces.rename(key.machineId, key.spaceId, open.renameDraft)) {
                is SpaceResult.Done -> sheets.value = sheets.value.copy(renameFor = null, renameProblem = null)
                is SpaceResult.Rejected -> sheets.value = sheets.value.copy(renameProblem = problemOf(result.field, result.reason))
                // Offline: nothing was written. The sheet stays open, so the user can cancel; the row is dimmed.
                SpaceResult.MachineOffline -> Unit

                is SpaceResult.UnknownSpace -> sheets.value = sheets.value.copy(renameFor = null)
            }
        }
    }

    /** Closes the rename sheet without saving. */
    fun cancelRename() {
        sheets.value = sheets.value.copy(renameFor = null, renameProblem = null)
    }

    /** Opens the close confirmation for the menu's space. */
    fun askClose() {
        val key = sheets.value.menuFor ?: return
        val ref = refOf(key) ?: return
        if (ref.offline) return
        sheets.value = sheets.value.copy(menuFor = null, closeFor = key)
    }

    /** Closes the space: its live agents are stopped, then it leaves the document. */
    fun confirmClose() {
        val key = sheets.value.closeFor ?: return
        sheets.value = sheets.value.copy(closeFor = null)
        viewModelScope.launch { spaces.close(key.machineId, key.spaceId) }
    }

    /** Keeps the space: the confirmation closes. */
    fun cancelClose() {
        sheets.value = sheets.value.copy(closeFor = null)
    }

    private fun move(up: Boolean) {
        val key = sheets.value.menuFor ?: return
        val ref = refOf(key) ?: return
        val allowed = if (up) ref.canMoveUp else ref.canMoveDown
        if (ref.offline || !allowed) return
        sheets.value = sheets.value.copy(menuFor = null)
        viewModelScope.launch {
            if (up) spaces.moveUp(key.machineId, key.spaceId) else spaces.moveDown(key.machineId, key.spaceId)
        }
    }

    private fun editNewSpace(change: (NewDraft) -> NewDraft) {
        val current = sheets.value.newSpace ?: return
        sheets.value = sheets.value.copy(newSpace = change(current))
    }

    /** The row of a space in the current state, as a sheet's target. */
    private fun refOf(key: SpaceKey): SpaceRef? {
        for (group in state.value.groups) {
            if (group.machineId != key.machineId) continue
            val row = group.rows.firstOrNull { it.spaceId == key.spaceId } ?: continue
            return SpaceRef(row.machineId, row.spaceId, row.name, row.pinned, row.canMoveUp, row.canMoveDown, row.dimmed)
        }
        return null
    }

    private fun build(
        groups: List<MachineSpaces>,
        agentList: List<MachineAgent>,
        machineList: List<Machine>,
        open: Sheets,
    ): SpacesUiState {
        val groupUi = groups.map { group -> groupOf(group, agentList, machineList) }
        val choices = machineList.map { machine ->
            MachineChoice(
                id = machine.id,
                name = machine.name,
                address = machineAddress(machine),
                offline = groups.firstOrNull { it.machineId == machine.id }?.offline ?: true,
            )
        }
        val rowsByKey = groupUi.flatMap { it.rows }.associateBy { SpaceKey(it.machineId, it.spaceId) }
        fun refFor(key: SpaceKey?): SpaceRef? = key?.let { rowsByKey[it] }?.let {
            SpaceRef(it.machineId, it.spaceId, it.name, it.pinned, it.canMoveUp, it.canMoveDown, it.dimmed)
        }
        val menuRef = refFor(open.menuFor)
        val renameRef = refFor(open.renameFor)
        val closeRef = refFor(open.closeFor)
        return SpacesUiState(
            groups = groupUi,
            hasMachines = machineList.isNotEmpty(),
            isEmpty = groupUi.all { it.rows.isEmpty() },
            newSpace = open.newSpace?.let { newSpaceUi(it, choices) },
            menu = menuRef,
            rename = renameRef?.let { RenameUi(it, open.renameDraft, open.renameProblem) },
            close = closeRef?.let { ref ->
                val live = agentList.count { it.machineId == ref.machineId && it.spaceId == ref.spaceId && it.agent.terminal.state == TerminalState.Live }
                CloseUi(ref, live)
            },
        )
    }

    private fun groupOf(group: MachineSpaces, agentList: List<MachineAgent>, machineList: List<Machine>): SpaceGroup {
        val machine = machineList.firstOrNull { it.id == group.machineId }
        val rules = moveRules(group.spaces)
        return SpaceGroup(
            machineId = group.machineId,
            name = machine?.name ?: group.machineId,
            address = machine?.let { machineAddress(it) }.orEmpty(),
            offline = group.offline,
            rows = group.spaces.mapIndexed { index, space ->
                val mine = agentList.filter { it.machineId == group.machineId && it.spaceId == space.id }
                SpaceRow(
                    machineId = group.machineId,
                    spaceId = space.id,
                    name = space.name,
                    folder = space.folder,
                    pinned = space.pinned,
                    agents = mine.map { agent ->
                        val terminal = agent.agent.terminal
                        AgentDot(terminal.status, terminal.state == TerminalState.Live, terminal.isWaitingForUser())
                    },
                    waitingCount = mine.count { it.agent.terminal.isWaitingForUser() },
                    folderState = folderStateOf(group.resolutionOf(space)),
                    canMoveUp = rules[index].first,
                    canMoveDown = rules[index].second,
                    dimmed = group.offline,
                )
            },
        )
    }

    private fun newSpaceUi(draft: NewDraft, choices: List<MachineChoice>): NewSpaceUi = NewSpaceUi(
        machines = choices,
        selectedId = draft.selectedId ?: choices.firstOrNull()?.id,
        folder = draft.folder,
        name = draft.name,
        defaultName = defaultSpaceName(draft.folder.ifBlank { DEFAULT_FOLDER }),
        folderProblem = draft.folderProblem,
        nameProblem = draft.nameProblem,
        machineOffline = draft.machineOffline,
    )
}
