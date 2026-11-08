package io.github.werlang.whistl.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.MachinesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A card's status pill (SPEC §2.1). Host-key states are shown as errors that need the user. */
enum class MachineStatus {
    /** Connected: the session is open and `hello` succeeded. */
    Connected,

    /** A connection attempt is in progress. */
    Connecting,

    /** Not connected; a retry may be scheduled. */
    Offline,

    /** The last attempt needs the user or failed for a reason that does not retry on its own. */
    Error,
}

/** The reason an [MachineStatus.Error] card shows, as a short typed label. */
enum class MachineIssue {
    /** The login was refused. */
    LoginRefused,

    /** The machine runs an hc protocol generation this app does not speak. */
    UnsupportedCore,

    /** The machine presented a host key nobody has confirmed. */
    HostKeyToConfirm,

    /** The machine presented a key other than the trusted one. */
    HostKeyChanged,

    /** Nothing listens on the SSH port of the machine (the host answered). */
    ConnectionRefused,

    /** The connection failed inside the app, not on the network. */
    Internal,
}

/**
 * One card of the Machines list (SPEC §2.1).
 *
 * @property id the machine id.
 * @property name the display name.
 * @property address the machine's address, shown as `user@host[:port]`.
 * @property status the status pill.
 * @property issue the reason, for an error.
 * @property agentCount the agents on the machine, or `null` when it is not connected.
 * @property version the hc version, or `null` when it is not connected.
 * @property installed the names of the installed agents, from the last detection, or `null` when none was made.
 * @property reconnectEnabled whether Reconnect can be tapped (not while connecting).
 */
data class MachineCard(
    val id: String,
    val name: String,
    val address: MachineAddress,
    val status: MachineStatus,
    val issue: MachineIssue?,
    val agentCount: Int?,
    val version: String?,
    val installed: List<String>?,
    val reconnectEnabled: Boolean,
)

/**
 * The card menu's machine.
 *
 * @property id the machine id.
 * @property name the display name.
 * @property address the machine's address.
 * @property reconnectEnabled whether Reconnect can be tapped.
 */
data class MachineMenu(
    val id: String,
    val name: String,
    val address: MachineAddress,
    val reconnectEnabled: Boolean,
)

/**
 * A host-key decision a card needs: the sheet over the Machines list asks for it.
 */
sealed interface HostKeyPrompt {
    /** The machine. */
    val machineId: String

    /** The machine's display name. */
    val name: String

    /** The machine's address. */
    val address: MachineAddress

    /** The connection state the prompt is for; dismissing stores it, so the same state does not reopen the sheet. */
    val connection: ConnectionState

    /**
     * First contact: the key is unknown, and trusting it is the way forward.
     *
     * @property algorithm the presented key type.
     * @property fingerprint the presented key's SHA-256 fingerprint.
     */
    data class Unknown(
        override val machineId: String,
        override val name: String,
        override val address: MachineAddress,
        override val connection: ConnectionState,
        val algorithm: String,
        val fingerprint: String,
    ) : HostKeyPrompt

    /**
     * The key changed since it was trusted: the connection is refused, and nothing here can trust the new key.
     *
     * @property algorithm the presented key type.
     * @property expected the fingerprint of the trusted key.
     * @property actual the fingerprint of the presented key.
     */
    data class Changed(
        override val machineId: String,
        override val name: String,
        override val address: MachineAddress,
        override val connection: ConnectionState,
        val algorithm: String,
        val expected: String,
        val actual: String,
    ) : HostKeyPrompt
}

/**
 * A problem the Machines screen reports instead of failing (phase 6 P0-2): the machine a request named is no longer added.
 */
enum class MachinesProblem {
    /** The machine is no longer added: the action could not be done. */
    MachineMissing,
}

/**
 * What the Machines screen shows.
 *
 * @property cards one card per machine, in the order the user added them.
 * @property menu the card menu open on a machine, or `null`.
 * @property confirmRemove the machine whose removal is being confirmed, or `null`.
 * @property hostKey the host-key sheet to show, or `null`.
 * @property addSession the Add machine sheet's session, or `null` when it is closed. A new session is a new form.
 * @property problem the problem the last action reported, or `null`.
 */
data class MachinesUiState(
    val cards: List<MachineCard>,
    val menu: MachineMenu?,
    val confirmRemove: MachineMenu?,
    val hostKey: HostKeyPrompt?,
    val addSession: Int?,
    val problem: MachinesProblem? = null,
)

/** The sheets the Machines screen can have open, and what the user dismissed. */
private data class Sheets(
    val menuFor: String? = null,
    val removeFor: String? = null,
    val addSession: Int? = null,
    val dismissed: Map<String, ConnectionState> = emptyMap(),
    val problem: MachinesProblem? = null,
)

/**
 * The Machines screen (SPEC §2.1, phase 4b r1): one card per machine with its connection, agents, version and installed agents,
 * the card menu (Reconnect, Remove), the confirmation before a removal, and the host-key sheet for a card. Events in
 * ([openMenu], [reconnect], [askRemove], [confirmRemove], [trust], [dismissHostKey], [openAdd]), state out ([state]).
 *
 * @param machines the machines, their connections, and removal.
 * @param agents the agents, for each card's count.
 * @param installed the installed agents of each machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachinesViewModel(
    private val machines: MachinesRepository,
    agents: AgentsRepository,
    installed: InstalledAgentsRepository,
) : ViewModel() {
    private val sheets = MutableStateFlow(Sheets())
    private var nextSession = 1

    /** Every machine's connection state, by id. Re-evaluated when the list of machines changes. */
    private val connections: Flow<Map<String, ConnectionState>> = machines.machines().flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(list.map { machine -> machines.observe(machine.id).map { machine.id to it } }) { pairs -> pairs.toMap() }
        }
    }

    /** The screen's state. */
    val state: StateFlow<MachinesUiState> = combine(
        machines.machines(),
        connections,
        agents.agents,
        installed.installed,
        sheets,
    ) { list, connection, agentList, installedMap, open ->
        val cards = list.map { machine ->
            val state = connection[machine.id] ?: ConnectionState.Offline
            val (status, issue) = classify(state)
            MachineCard(
                id = machine.id,
                name = machine.name,
                address = machine.address(),
                status = status,
                issue = issue,
                agentCount = (state as? ConnectionState.Connected)?.let { _ -> agentList.count { it.machineId == machine.id } },
                version = (state as? ConnectionState.Connected)?.hello?.version,
                installed = installedMap[machine.id]?.map { it.displayName },
                reconnectEnabled = state !is ConnectionState.Connecting,
            )
        }
        val hostKeyPrompt = if (open.menuFor != null || open.removeFor != null || open.addSession != null) {
            null
        } else {
            list.firstNotNullOfOrNull { machine ->
                val state = connection[machine.id]
                // Identity, not equality: a new attempt that reaches the same host-key state is a new event and reopens the sheet.
                if (state == null || open.dismissed[machine.id] === state) null else hostKeyPromptFor(machine.id, machine.name, machine.address(), state)
            }
        }
        MachinesUiState(
            cards = cards,
            menu = open.menuFor?.let { id -> list.firstOrNull { it.id == id }?.let { menuOf(it, connection[it.id]) } },
            confirmRemove = open.removeFor?.let { id -> list.firstOrNull { it.id == id }?.let { menuOf(it, connection[it.id]) } },
            hostKey = hostKeyPrompt,
            addSession = open.addSession,
            problem = open.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MachinesUiState(emptyList(), null, null, null, null))

    /**
     * Opens the card menu of a machine.
     *
     * @param id the machine.
     */
    fun openMenu(id: String) {
        sheets.value = sheets.value.copy(menuFor = id, problem = null)
    }

    /** Closes the card menu. */
    fun closeMenu() {
        sheets.value = sheets.value.copy(menuFor = null)
    }

    /**
     * Restarts the machine's connection attempt. Nothing happens while it is connecting.
     *
     * @param id the machine.
     */
    fun reconnect(id: String) {
        sheets.value = sheets.value.copy(menuFor = null, problem = null)
        viewModelScope.launch {
            try {
                machines.reconnect(id)
            } catch (e: NoSuchElementException) {
                reportMissing()
            }
        }
    }

    /**
     * Asks for confirmation before a machine is removed. The menu closes.
     *
     * @param id the machine.
     */
    fun askRemove(id: String) {
        sheets.value = sheets.value.copy(menuFor = null, removeFor = id)
    }

    /** Cancels the removal. */
    fun cancelRemove() {
        sheets.value = sheets.value.copy(removeFor = null)
    }

    /**
     * Removes the machine that was asked about: its session closes, its spaces leave the app, and its agents keep running on the
     * machine (SPEC §2.1).
     */
    fun confirmRemove() {
        val id = sheets.value.removeFor ?: return
        sheets.value = sheets.value.copy(removeFor = null)
        viewModelScope.launch { machines.remove(id) }
    }

    /**
     * Trusts the presented host key of a machine and connects (SPEC §6). Only an unknown key can be trusted: a changed key is
     * refused, and this does nothing for it.
     *
     * @param id the machine.
     */
    fun trust(id: String) {
        val state = connectionOf(id)
        if (state == null) {
            reportMissing()
            return
        }
        if (state !is ConnectionState.HostKeyUnknown) return
        viewModelScope.launch {
            try {
                machines.confirmHostKey(id)
            } catch (e: NoSuchElementException) {
                reportMissing()
            }
        }
    }

    /**
     * Dismisses the host-key sheet for the state it shows. It returns when the machine's state changes.
     *
     * @param prompt the sheet's prompt.
     */
    fun dismissHostKey(prompt: HostKeyPrompt) {
        sheets.value = sheets.value.copy(dismissed = sheets.value.dismissed + (prompt.machineId to prompt.connection))
    }

    /** Opens the Add machine sheet with a new, empty form. */
    fun openAdd() {
        sheets.value = sheets.value.copy(addSession = nextSession++, problem = null)
    }

    /** Closes the Add machine sheet. */
    fun closeAdd() {
        sheets.value = sheets.value.copy(addSession = null)
    }

    /** The machine's connection state, or `null` when the machine is no longer added. */
    private fun connectionOf(id: String): ConnectionState? = try {
        machines.observe(id).value
    } catch (e: NoSuchElementException) {
        null
    }

    private fun reportMissing() {
        sheets.value = sheets.value.copy(problem = MachinesProblem.MachineMissing)
    }

    private fun menuOf(machine: io.github.werlang.whistl.domain.Machine, state: ConnectionState?): MachineMenu = MachineMenu(
        id = machine.id,
        name = machine.name,
        address = machine.address(),
        reconnectEnabled = state !is ConnectionState.Connecting,
    )

    private fun hostKeyPromptFor(id: String, name: String, address: MachineAddress, state: ConnectionState): HostKeyPrompt? = when (state) {
        is ConnectionState.HostKeyUnknown -> HostKeyPrompt.Unknown(id, name, address, state, state.algorithm, state.fingerprint)
        is ConnectionState.HostKeyChanged -> HostKeyPrompt.Changed(id, name, address, state, state.algorithm, state.expected, state.actual)
        else -> null
    }
}

/**
 * The status pill and the issue for a connection state. Host-key states are errors that need the user, with their own issue.
 *
 * @param state the connection state.
 * @return the status and, for an error, the issue.
 */
internal fun classify(state: ConnectionState): Pair<MachineStatus, MachineIssue?> = when (state) {
    ConnectionState.Connecting -> MachineStatus.Connecting to null
    is ConnectionState.Connected -> MachineStatus.Connected to null
    ConnectionState.Offline -> MachineStatus.Offline to null
    is ConnectionState.Error -> MachineStatus.Error to when (state.kind) {
        ErrorKind.AuthFailed -> MachineIssue.LoginRefused
        ErrorKind.UnsupportedGeneration -> MachineIssue.UnsupportedCore
        ErrorKind.ConnectionRefused -> MachineIssue.ConnectionRefused
        ErrorKind.Internal -> MachineIssue.Internal
    }
    is ConnectionState.HostKeyUnknown -> MachineStatus.Error to MachineIssue.HostKeyToConfirm
    is ConnectionState.HostKeyChanged -> MachineStatus.Error to MachineIssue.HostKeyChanged
}
