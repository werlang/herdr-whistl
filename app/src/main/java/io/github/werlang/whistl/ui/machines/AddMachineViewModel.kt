package io.github.werlang.whistl.ui.machines

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.AuthMethod
import io.github.werlang.whistl.data.ClientPublicKey
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.EditResult
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.NewMachine
import io.github.werlang.whistl.domain.Machine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How the machine logs in. */
enum class AuthChoice {
    /** The app's SSH key. The default. */
    Key,

    /** A password, saved encrypted. */
    Password,
}

/** Where the Add machine flow is. */
enum class AddStage {
    /** The form is open and can be submitted. */
    Form,

    /** The machine was added and a connection attempt is in progress. */
    Connecting,

    /** The machine presented a host key nobody has confirmed: the sheet asks the user to trust it. */
    HostKeyUnknown,

    /** The machine presented a key other than the trusted one: the connection is refused, with no trust action. */
    HostKeyChanged,

    /** The login was refused. Retry tries once more. */
    LoginRefused,

    /** The machine could not be reached; the app keeps trying while the sheet is open. */
    Unreachable,

    /** The machine runs an hc protocol generation this app does not speak. */
    UnsupportedCore,

    /** The host answered but nothing listens on the SSH port. Retry tries once more. */
    ConnectionRefused,

    /** The connection failed inside the app, not on the network. Retry tries once more. */
    Internal,

    /** The login worked, but herdr-core is not installed for it on the machine. Retry tries once more, after installing it. */
    CoreMissing,

    /** The machine is connected: the sheet closes. */
    Connected,

    /** The machine is no longer added (removed elsewhere, or never stored): nothing can be done with it. Close shows (phase 6 P0-2). */
    Missing,
}

/**
 * What the Add machine sheet shows.
 *
 * The password is not here: its text lives in the sheet's own field and reaches the view model only at submit, as a
 * `CharArray`.
 *
 * @property host the host as typed.
 * @property user the user name as typed.
 * @property port the port as typed; blank means 22.
 * @property name the display name as typed; blank means the default.
 * @property defaultName the name that is used when [name] is blank, shown as its placeholder.
 * @property auth the login method.
 * @property stage where the flow is.
 * @property problems the problem of each field, after the last submit.
 * @property hostKeyAlgorithm the presented key type, in the host-key stages.
 * @property hostKeyFingerprint the presented key's fingerprint, in the unknown stage.
 * @property trustedFingerprint the fingerprint of the trusted key, in the changed stage.
 * @property presentedFingerprint the presented key's fingerprint, in the changed stage.
 * @property machineId the id of the machine once it was added.
 * @property machineName the name of the machine once it was added.
 * @property machineAddress the address of the machine once it was added, or `null` before that.
 * @property publicKey this device's public key line, for key mode.
 * @property publicKeyCopied whether the key was copied since the sheet opened.
 * @property canConnect whether Connect can be tapped: the form is open and host and user are filled.
 * @property closed `true` when the sheet should close.
 */
data class AddMachineUiState(
    val host: String,
    val user: String,
    val port: String,
    val name: String,
    val defaultName: String,
    val auth: AuthChoice,
    val stage: AddStage,
    val problems: Map<AddField, FieldProblem>,
    val hostKeyAlgorithm: String?,
    val hostKeyFingerprint: String?,
    val trustedFingerprint: String?,
    val presentedFingerprint: String?,
    val machineId: String?,
    val machineName: String?,
    val machineAddress: MachineAddress?,
    val publicKey: String,
    val publicKeyCopied: Boolean,
    val canConnect: Boolean,
    val closed: Boolean,
    /** `true` in the Edit machine sheet: the fields are the stored machine's, the title says Edit, and Connect is Save. */
    val editing: Boolean = false,
    /** Edit mode: Save is enabled only when the form is valid and something differs from the stored machine. */
    val canSave: Boolean = false,
)

/**
 * The Add machine sheet (SPEC §2.1, phase 4b §2.5, §2.6): a form, then the first connection. Connect checks the form and adds the
 * machine through the repository; the connection state then drives the sheet: a host key to trust, a blocking changed key, a
 * refused login, an unreachable machine, or connected, which closes it. Events in, state out.
 *
 * Edit machine (phase 7 P2) is the same sheet with [editing] set: the form starts with the stored machine, and Connect is Save.
 * Save edits the machine in place, and follows the new host's first connection only when the host changed.
 *
 * @param machines adds the machine and follows its connection.
 * @param clientKey this device's public key line.
 * @param clipboard copies the public key.
 * @param editing the id of the machine to edit, or `null` for Add machine.
 */
class AddMachineViewModel(
    private val machines: MachinesRepository,
    clientKey: ClientPublicKey,
    private val clipboard: ClipboardWriter,
    private val editing: String? = null,
) : ViewModel() {
    private val publicKey: String = clientKey.publicKeyLine()

    /** Edit mode: the stored machine as it was when the sheet opened, the baseline Save compares with. */
    private val origin: EditOrigin? = editing?.let { loadOrigin(it) }

    private val state = MutableStateFlow(
        AddMachineUiState(
            host = origin?.machine?.host.orEmpty(),
            user = origin?.machine?.user.orEmpty(),
            port = origin?.machine?.port?.toString().orEmpty(),
            name = origin?.machine?.name.orEmpty(),
            defaultName = origin?.machine?.host?.let { defaultFor(it) }.orEmpty(),
            auth = origin?.auth ?: AuthChoice.Key,
            stage = if (editing != null && origin == null) AddStage.Missing else AddStage.Form,
            problems = emptyMap(),
            hostKeyAlgorithm = null,
            hostKeyFingerprint = null,
            trustedFingerprint = null,
            presentedFingerprint = null,
            machineId = origin?.machine?.id,
            machineName = origin?.machine?.name,
            machineAddress = origin?.machine?.address(),
            publicKey = publicKey,
            publicKeyCopied = false,
            canConnect = false,
            closed = false,
            editing = editing != null,
            canSave = false,
        ),
    )

    /** Edit mode: whether the sheet's password field has text. The text itself stays in the sheet. */
    private var passwordEntered = false
    private var observer: Job? = null
    private var attempted = false

    /** The sheet's state. */
    val uiState: StateFlow<AddMachineUiState> = state

    /**
     * The host field changed.
     *
     * @param text the text typed.
     */
    fun onHost(text: String) = edit { it.copy(host = text, defaultName = defaultFor(text)) }

    /**
     * The user field changed.
     *
     * @param text the text typed.
     */
    fun onUser(text: String) = edit { it.copy(user = text) }

    /**
     * The port field changed. The text is kept as typed: a port that is not a number is reported by the form, under the field.
     *
     * @param text the text typed.
     */
    fun onPort(text: String) = edit { it.copy(port = text) }

    /**
     * The display name field changed.
     *
     * @param text the text typed.
     */
    fun onName(text: String) = edit { it.copy(name = text) }

    /**
     * The login method changed.
     *
     * @param choice the method.
     */
    fun onAuth(choice: AuthChoice) = edit { it.copy(auth = choice) }

    /**
     * Connect: checks the form, and adds the machine. The password, in password mode, is given here and nowhere else.
     *
     * @param password the password, or `null` in key mode. The repository reads it once.
     */
    fun connect(password: CharArray?) {
        val current = state.value
        if (current.stage != AddStage.Form) return
        if (editing != null) {
            saveEdit(password)
            return
        }
        val passwordMode = current.auth == AuthChoice.Password
        val passwordGiven = if (passwordMode) password else null
        when (val check = checkAddForm(current.host, current.user, current.port, current.name, passwordMode, passwordGiven)) {
            is FormCheck.Invalid -> state.value = current.copy(problems = check.problems, canConnect = canConnect(current))
            is FormCheck.Valid -> {
                state.value = current.copy(problems = emptyMap(), stage = AddStage.Connecting)
                viewModelScope.launch {
                    val draft = check.draft
                    val machine = NewMachine(name = draft.name, host = draft.host, port = draft.port, user = draft.user)
                    when (val result = machines.add(machine, passwordGiven)) {
                        is AddResult.Added -> {
                            state.value = state.value.copy(
                                machineId = result.machine.id,
                                machineName = result.machine.name,
                                machineAddress = result.machine.address(),
                            )
                            follow(result.machine.id)
                        }

                        is AddResult.Rejected -> state.value = state.value.copy(
                            stage = AddStage.Form,
                            problems = mapOf(addFieldFor(result.field) to problemFor(result.field, result.reason)),
                        )
                    }
                }
            }
        }
    }

    /**
     * Edit mode: the password field's text is empty or not. The sheet reports it, so Save knows whether a new password was typed
     * (a new password counts as a change) or the stored one is kept.
     *
     * @param entered whether the password field has text.
     */
    fun onPasswordEntered(entered: Boolean) {
        passwordEntered = entered
        state.value = state.value.copy(canSave = canSave(state.value))
    }

    /**
     * Edit mode, Save: checks the form, then edits the stored machine in place (rules R1–R4 in `MachinesRepository.edit`). A
     * changed host follows the new host's first connection, so the sheet can ask for the new key; any other change closes the
     * sheet, and the machine reconnects in the background when it must.
     */
    private fun saveEdit(password: CharArray?) {
        val current = state.value
        val origin = origin ?: return
        val check = checkAddForm(current.host, current.user, current.port, current.name, passwordMode = false, password = null)
        if (check !is FormCheck.Valid || !canSave(current)) {
            state.value = current.copy(
                problems = (check as? FormCheck.Invalid)?.problems.orEmpty(),
                canSave = canSave(current),
            )
            return
        }
        val draft = check.draft
        val id = origin.machine.id
        val method = if (current.auth == AuthChoice.Password) AuthMethod.Password else AuthMethod.Key
        state.value = current.copy(problems = emptyMap())
        viewModelScope.launch {
            try {
                when (val result = machines.edit(id, NewMachine(draft.name, draft.host, draft.port, draft.user), method, password)) {
                    is EditResult.Saved -> if (draft.host != origin.machine.host) {
                        // Rule R3: the new host has no pinned key, so its key is confirmed on the next connection.
                        state.value = state.value.copy(
                            stage = AddStage.Connecting,
                            machineName = result.machine.name,
                            machineAddress = result.machine.address(),
                        )
                        follow(id)
                    } else {
                        state.value = state.value.copy(closed = true)
                    }

                    is EditResult.Rejected -> state.value = state.value.copy(
                        problems = mapOf(addFieldFor(result.field) to problemFor(result.field, result.reason)),
                    )
                }
            } catch (e: NoSuchElementException) {
                missing()
            }
        }
    }

    /**
     * Edit mode: Save is enabled when the form is valid, something differs from the stored machine, and a password is available
     * for the login method (typed now, or already saved for password login). Nothing is enabled outside the form stage.
     *
     * @param next the state to judge.
     * @return whether Save can be tapped.
     */
    private fun canSave(next: AddMachineUiState): Boolean {
        val origin = origin ?: return false
        if (next.stage != AddStage.Form) return false
        val draft = (checkAddForm(next.host, next.user, next.port, next.name, passwordMode = false, password = null) as? FormCheck.Valid)
            ?.draft ?: return false
        val stored = origin.machine
        val changed = draft.host != stored.host || draft.user != stored.user || draft.port != stored.port ||
            draft.name != stored.name || next.auth != origin.auth ||
            (next.auth == AuthChoice.Password && passwordEntered)
        val passwordReady = next.auth == AuthChoice.Key || passwordEntered || origin.auth == AuthChoice.Password
        return changed && passwordReady
    }

    /**
     * The stored machine and how it logs in, when it is still added. The password is never read here.
     *
     * @param id the machine id.
     * @return the origin, or `null` when the machine is no longer added.
     */
    private fun loadOrigin(id: String): EditOrigin? {
        val machine = machines.machines().value.firstOrNull { it.id == id } ?: return null
        val auth = try {
            machines.authOf(id)
        } catch (e: NoSuchElementException) {
            return null
        }
        return EditOrigin(machine, if (auth == AuthMethod.Password) AuthChoice.Password else AuthChoice.Key)
    }

    /**
     * The machine an Edit sheet started from, and its login method.
     *
     * @property machine the stored machine.
     * @property auth the stored login method, as the sheet shows it.
     */
    private class EditOrigin(val machine: Machine, val auth: AuthChoice)

    /**
     * Trust and connect: pins the presented host key and connects (SPEC §6). Only the unknown-key stage has this action; a
     * changed key is refused and nothing here can trust it.
     */
    fun trust() {
        if (state.value.stage != AddStage.HostKeyUnknown) return
        val id = state.value.machineId ?: return
        viewModelScope.launch {
            try {
                machines.confirmHostKey(id)
            } catch (e: NoSuchElementException) {
                missing()
            }
        }
    }

    /** Retry: tries the connection once more after a refused login or an unreachable machine. */
    fun retry() {
        val id = state.value.machineId ?: return
        viewModelScope.launch {
            try {
                machines.reconnect(id)
            } catch (e: NoSuchElementException) {
                missing()
            }
        }
    }

    /** Remove machine: forgets the machine that was added, from the changed-key stage. The sheet closes. */
    fun removeMachine() {
        val id = state.value.machineId ?: return
        observer?.cancel()
        viewModelScope.launch {
            machines.remove(id)
            state.value = state.value.copy(closed = true)
        }
    }

    /** Copies this device's public key to the clipboard. */
    fun copyPublicKey() {
        clipboard.copy(publicKey)
        state.value = state.value.copy(publicKeyCopied = true)
    }

    /** Closes the sheet. A machine that was added stays in the list, and keeps its connection. */
    fun close() {
        observer?.cancel()
        state.value = state.value.copy(closed = true)
    }

    override fun onCleared() {
        observer?.cancel()
    }

    /** Follows the new machine's connection state until the sheet closes. */
    private fun follow(id: String) {
        observer?.cancel()
        // observe() throws for a machine that is no longer added: the sheet says so rather than failing (phase 6 P0-2).
        val connection = try {
            machines.observe(id)
        } catch (e: NoSuchElementException) {
            missing()
            return
        }
        observer = viewModelScope.launch {
            connection.collect { state -> apply(state) }
        }
    }

    /** The machine is no longer added: the sheet shows that, and stops following it. */
    private fun missing() {
        observer?.cancel()
        state.value = state.value.copy(stage = AddStage.Missing)
    }

    /** Maps a connection state to the sheet's stage. */
    private fun apply(connection: ConnectionState) {
        val current = state.value
        state.value = when (connection) {
            ConnectionState.Connecting -> {
                attempted = true
                current.copy(stage = AddStage.Connecting)
            }

            ConnectionState.Offline -> current.copy(stage = if (attempted) AddStage.Unreachable else AddStage.Connecting)
            is ConnectionState.Connected -> current.copy(stage = AddStage.Connected, closed = true)
            is ConnectionState.HostKeyUnknown -> current.copy(
                stage = AddStage.HostKeyUnknown,
                hostKeyAlgorithm = connection.algorithm,
                hostKeyFingerprint = connection.fingerprint,
            )

            is ConnectionState.HostKeyChanged -> current.copy(
                stage = AddStage.HostKeyChanged,
                hostKeyAlgorithm = connection.algorithm,
                trustedFingerprint = connection.expected,
                presentedFingerprint = connection.actual,
            )

            is ConnectionState.Error -> current.copy(
                stage = when (connection.kind) {
                    ErrorKind.AuthFailed -> AddStage.LoginRefused
                    ErrorKind.UnsupportedGeneration -> AddStage.UnsupportedCore
                    ErrorKind.ConnectionRefused -> AddStage.ConnectionRefused
                    ErrorKind.Internal -> AddStage.Internal
                    ErrorKind.CoreMissing -> AddStage.CoreMissing
                },
            )
        }
    }

    private fun edit(change: (AddMachineUiState) -> AddMachineUiState) {
        val next = change(state.value)
        state.value = next.copy(canConnect = canConnect(next), canSave = canSave(next))
    }

    private fun canConnect(current: AddMachineUiState): Boolean =
        current.stage == AddStage.Form && current.host.isNotBlank() && current.user.isNotBlank()

    /** The name a blank display name takes for this host text, or empty while the host is not valid. */
    private fun defaultFor(host: String): String = when (val r = io.github.werlang.whistl.domain.validateHost(host.trim())) {
        is io.github.werlang.whistl.domain.Validation.Valid -> defaultMachineName(r.value)
        is io.github.werlang.whistl.domain.Validation.Invalid -> ""
    }
}
