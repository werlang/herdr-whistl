package io.github.werlang.whistl.ui.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.LimitsRepository
import io.github.werlang.whistl.data.LimitsResult
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.PinnedAgents
import io.github.werlang.whistl.data.SettingsRepository
import io.github.werlang.whistl.data.TerminalAction
import io.github.werlang.whistl.data.TerminalHandle
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.TerminalScreen
import io.github.werlang.whistl.data.canReconnect
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Keystrokes
import io.github.werlang.whistl.domain.KeyAction
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.domain.TerminalFrame
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.domain.action
import io.github.werlang.whistl.domain.ctrlCombination
import io.github.werlang.whistl.domain.letterCombination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the screen cannot type or send, or why the last action did not go through. */
enum class AgentNotice {
    /** The machine is not connected: nothing can be sent until it connects. */
    Offline,

    /** The terminal is no longer on the machine. */
    Gone,

    /** The program has finished: the screen is read-only. */
    Finished,

    /** The last send was refused because the program is waiting for the user. Shown until the next action. */
    Blocked,

    /** The last action was refused by hc for another reason. Shown until the next action. */
    Refused,

    /** A typed character is not a key the core can press. Shown until the next action. */
    NotTypable,

    /** Shown once, after the first line is sent: holding Send presses the text as keys (0.6.2). */
    HoldToSendKeys,
}

/** The status label the screen shows next to the status dot (SPEC §2.5). */
enum class AgentStatusLabel {
    /** Working. */
    Working,

    /** Waiting for the user (blocked). */
    NeedsYou,

    /** Finished its last task. */
    Done,

    /** Waiting for input. */
    Idle,

    /** The status is an error. */
    Error,

    /** A plain program with no status, such as a shell. */
    Plain,

    /** The program has exited. */
    Finished,

    /** The machine is not connected, so the agent is shown as it was last seen (phase 7 r7). */
    Offline,
}

/**
 * What the Agent screen shows.
 *
 * @property name the agent's name.
 * @property address the terminal address.
 * @property machineId the machine the agent runs on, for the action sheet's row.
 * @property machineName the machine's name.
 * @property spaceName the space's name, or `null` for "No space".
 * @property statusLabel the label next to the status dot.
 * @property status the raw status, for the dot's colour; `null` for a plain program.
 * @property live whether the terminal is live: a finished agent has nothing to interrupt.
 * @property frame the terminal as the emulator holds it: history and the screen rows, with the cursor.
 * @property reach whether the output is current.
 * @property canReconnect whether the header offers Reconnect for the machine (SPEC §2.5).
 * @property input the text field, with its cursor.
 * @property ctrlArmed whether the sticky Ctrl is armed for the next key.
 * @property keyBar the shortcut keys, in the order the Settings screen holds them.
 * @property lastKey the last key sent, for a short hint: `null` when none.
 * @property notice why input is disabled, or the last refusal.
 * @property canType whether the text field and the key bar take input.
 * @property canSend whether Send is enabled: input allowed and the field not empty.
 * @property blockedAgent whether the agent is blocked, so the screen explains that Send forces the text.
 * @property menuOpen whether the action sheet is open.
 * @property confirmingClose whether the close-and-remove question is open inside the action sheet.
 * @property closed `true` once the agent was closed and removed: the screen navigates back.
 * @property pinned whether the agent is pinned on this device.
 * @property fontSize the terminal font size from Settings.
 */
data class AgentUiState(
    val name: String,
    val address: String,
    val machineId: String,
    val machineName: String,
    /** The user the machine logs in as: what the empty input field says (0.6.0). */
    val user: String,
    val spaceName: String?,
    val statusLabel: AgentStatusLabel,
    val status: AgentStatus?,
    val live: Boolean,
    val frame: TerminalFrame,
    val reach: TerminalReach,
    val canReconnect: Boolean,
    val input: TextFieldValue,
    val ctrlArmed: Boolean,
    val keyBar: List<ShortcutKey>,
    val lastKey: SentKey?,
    val notice: AgentNotice?,
    val canType: Boolean,
    val canSend: Boolean,
    val blockedAgent: Boolean,
    val menuOpen: Boolean,
    val confirmingClose: Boolean,
    val closed: Boolean,
    val pinned: Boolean,
    val fontSize: FontSize,
    /** Whether the menu is asking for a new name, and what has been typed (0.7.6). */
    val renaming: Boolean = false,
    val renameDraft: String = "",
    /** The limits answer, once Check limits was tapped in the menu (0.7.6). */
    val limits: LimitsResult? = null,
    val limitsLoading: Boolean = false,
)

/**
 * The last key sent, for the hint line. The label is the key's own name; the screen adds "Ctrl+" through a string resource when
 * [ctrl] is set, so no visible text is built here (phase 6 P0-11).
 *
 * @property label the key's name, such as `Esc` or `C`.
 * @property ctrl whether the key was sent with Ctrl.
 */
data class SentKey(val label: String, val ctrl: Boolean = false)

/** The screen's own interaction state, combined with the repositories' state. */
private data class Interaction(
    val input: TextFieldValue = TextFieldValue(""),
    val ctrlArmed: Boolean = false,
    val lastKey: SentKey? = null,
    val refusal: AgentNotice? = null,
    /** Set when the terminal could not be opened: the machine or the terminal is no longer there (phase 6 P0-2). */
    val unavailable: Boolean = false,
    val menuOpen: Boolean = false,
    val confirmingClose: Boolean = false,
    val closed: Boolean = false,
    /** Whether the hold-to-send hint has already been shown for this screen (0.6.2). */
    val taughtHold: Boolean = false,
    /** The name being typed in the menu (0.7.6). */
    val renaming: Boolean = false,
    val renameDraft: String = "",
    /** The limits, asked from the menu on the tap (0.7.6). */
    val limits: LimitsResult? = null,
    val limitsLoading: Boolean = false,
)

/**
 * The Agent screen (SPEC §2.5): one terminal, its output, and the input, over a [TerminalHandle] held while the screen is open.
 *
 * Rules, all here:
 * - Send sends the text and presses Enter. A blocked program refuses plain text, so Send forces it only when the agent's
 *   status is `blocked`; for any other status the request has no `force` field.
 * - Ctrl is sticky. Armed, the next bar key goes out as `ctrl+<key>` when the table has that combination, and otherwise
 *   disarms and goes out as it is. Armed on an empty field, one typed letter goes out as `ctrl+<letter>` and is not inserted,
 *   and Ctrl disarms (phase 7 r9, user decision); other typing inserts and keeps Ctrl armed. Send never applies Ctrl: the
 *   text goes out plainly and clears the latch (phase 7 r5 amendment U3).
 * - `/ | ~ -` insert their character at the cursor. Every other bar key is sent at once with `terminal.keys`.
 * - Interrupt sends Ctrl+C. Close & Remove asks first, in the same action sheet the lists use, then kills and removes the
 *   terminal, and the screen closes.
 * - Input is disabled while the machine is offline, the terminal is gone, or the program has finished.
 * - While the machine has no connection (offline or connecting), the agent is shown as it was last seen: its status and its
 *   space stay, and its status bar says offline. Only a program the core reported finished says finished (phase 7 r7).
 * - Reconnect is offered while the machine can be retried (SPEC §2.5), and starts the same immediate attempt as the Machines
 *   card menu without leaving the screen.
 *
 * @param terminals opens the terminal.
 * @param agents the agent's status and state.
 * @param machines the machine's name and connection state.
 * @param settings the font size and the shortcut bar.
 * @param machineId the machine the agent runs on.
 * @param address the terminal address.
 * @param pins the pinned agents of this device, the same set the Agents list uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(
    private val terminals: TerminalRepository,
    agents: AgentsRepository,
    private val machines: MachinesRepository,
    settings: SettingsRepository,
    private val machineId: String,
    private val address: String,
    private val pins: PinnedAgents,
    private val agentsRepo: AgentsRepository = agents,
    private val limits: LimitsRepository? = null,
) : ViewModel() {
    private val handle = MutableStateFlow<TerminalHandle?>(null)
    private val interaction = MutableStateFlow(Interaction())

    /** The key the pin is stored under, the same one the Agents list and the Space detail use. */
    private val pinKey: String = PinnedAgents.key(machineId, address)

    /**
     * The machine's connection state. A machine that is no longer added has no state to observe — [MachinesRepository.observe]
     * throws for it — and reads as offline, which the screen already shows as a gone terminal rather than a crash (phase 6 P0-2).
     */
    private val connection = flow { emitAll(machines.observe(machineId)) }.catch { error ->
        if (error is NoSuchElementException) emit(ConnectionState.Offline) else throw error
    }

    /**
     * What the screen owns — its interaction state, whether this agent is pinned, and the machine's connection — as one flow,
     * so the screen's state stays a five-way combine.
     */
    private val ownState = combine(interaction, pins.pinned, connection) { own, keys, state ->
        Triple(own, pinKey in keys, state)
    }

    private val screen = handle.flatMapLatest { open ->
        open?.screen ?: flowOf(TerminalScreen(frame = TerminalFrame.empty(DEFAULT_COLS, DEFAULT_ROWS), reach = TerminalReach.Offline))
    }

    /** The pane's size in cells, as the screen last measured it. Kept until the terminal opens, then sent to it. */
    private var paneSize: Pair<Int, Int>? = null

    /**
     * The agent the machine lists now ([now]), and the last one it listed ([lastSeen]). The repository drops an offline
     * machine's agents from its list, so the screen keeps the last one while the machine has no connection (phase 7 r7).
     */
    private data class Sighting(val now: MachineAgent? = null, val lastSeen: MachineAgent? = null)

    private val agent = agents.agents
        .map { list -> list.firstOrNull { it.machineId == machineId && it.agent.terminal.address == address } }
        .scan(Sighting()) { seen, now -> Sighting(now = now, lastSeen = now ?: seen.lastSeen) }

    /** The screen's state. */
    val state: StateFlow<AgentUiState> = combine(
        screen,
        agent,
        machines.machines(),
        settings.settings,
        ownState,
    ) { output, sighting, machineList, prefs, (own, pinned, machineState) ->
        val machine = machineList.firstOrNull { it.id == machineId }
        build(
            output,
            sighting,
            machine?.name ?: machineId,
            machine?.user.orEmpty(),
            prefs.fontSize,
            prefs.shortcutBar,
            own,
            pinned,
            machineState,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, placeholder())

    init {
        viewModelScope.launch {
            // open() throws NoSuchElementException for a machine that is no longer added. The screen shows that, rather than
            // crashing the app (phase 6 P0-2).
            handle.value = try {
                terminals.open(machineId, address)
            } catch (e: NoSuchElementException) {
                interaction.update { it.copy(unavailable = true) }
                null
            }
            paneSize?.let { (cols, rows) -> handle.value?.resize(cols, rows) }
        }
    }

    /**
     * The pane that shows the terminal was measured: the terminal follows it, and the core gets the new size (SPEC §5.1).
     *
     * @param cols the pane's width in cells.
     * @param rows the pane's height in cells.
     */
    fun onPaneSize(cols: Int, rows: Int) {
        paneSize = cols to rows
        handle.value?.resize(cols, rows)
    }

    /**
     * The text field changed. Ctrl armed on an empty field sends one typed letter as its Ctrl combination (phase 7 r9, user
     * decision): `s` goes out as `ctrl+s`, the field stays empty, and Ctrl disarms. So `sleep` typed with Ctrl armed on an
     * empty field begins with Ctrl+S; that is the intended result, not a fault. Every other change only inserts and keeps Ctrl
     * armed: a digit or punctuation on an empty field, typing into a field that has text, and a paste or IME commit of more than
     * one character. A latched Ctrl stays armed until Send or a bar key. (Phase 7 r5: typing used to send the first letter as
     * Ctrl whatever the field held, so `sleep` began with Ctrl+S and froze the terminal. r5 made typing insert; r9 keeps that
     * for a field with text and for non-letters, and sends one letter as Ctrl only when Ctrl was armed on an empty field.)
     *
     * @param newValue the field after the change.
     */
    fun onInputChange(newValue: TextFieldValue) {
        val own = interaction.value
        // Ctrl takes the character that was just typed, whatever is already in the field: with a half-written line, `Ctrl`
        // then `x` used to insert an `x` instead of pressing `ctrl+x` (0.6.2).
        val added = newValue.text.removePrefix(own.input.text)
        val combination = when {
            !own.ctrlArmed || !state.value.canType -> null
            added.length == 1 -> letterCombination(added[0])
            else -> null
        }
        if (combination == null) {
            interaction.value = own.copy(input = newValue)
        } else {
            val letter = added[0].uppercaseChar().toString()
            interaction.value = own.copy(ctrlArmed = false, lastKey = SentKey(letter, ctrl = true))
            sendKeys(listOf(combination))
        }
    }

    /**
     * A bar key was tapped (or the Ctrl key). Ignored while input is disabled.
     *
     * @param key the key tapped.
     */
    fun onKey(key: ShortcutKey) {
        val current = state.value
        if (!current.canType) return
        val own = interaction.value
        when (val action = key.action()) {
            KeyAction.Modifier -> interaction.value = own.copy(ctrlArmed = !own.ctrlArmed)

            is KeyAction.Insert -> interaction.value = own.copy(
                input = own.input.insertAtCursor(action.char.toString()),
                ctrlArmed = false,
            )
            is KeyAction.Send -> {
                if (own.ctrlArmed) {
                    val combination = key.ctrlCombination()
                    val hint = if (combination != null) {
                        SentKey(key.label.trimStart('^'), ctrl = true)
                    } else {
                        SentKey(key.label)
                    }
                    interaction.value = own.copy(ctrlArmed = false, lastKey = hint)
                    sendKeys(listOf(combination ?: action.hcName))
                } else {
                    interaction.value = own.copy(lastKey = SentKey(key.label))
                    sendKeys(listOf(action.hcName))
                }
            }
        }
    }

    /**
     * Send: the field's text goes out unmodified, with Enter, and a blocked agent receives it forced. Send never applies a
     * latched Ctrl (phase 7 r5 amendment U3): Ctrl disarms, and only a bar key takes it. Empty text or no connection does nothing.
     */
    fun onSend() {
        val current = state.value
        if (!current.canSend) return
        val own = interaction.value
        val text = own.input.text
        val force = current.status == AgentStatus.Blocked
        interaction.value = own.copy(ctrlArmed = false)
        viewModelScope.launch {
            val outcome = handle.value?.send(text, force) ?: TerminalAction.Offline
            when (outcome) {
                TerminalAction.Done -> {
                    val now = interaction.value
                    // The first line a user sends teaches the gesture nobody would guess: sending is the visible half of
                    // the button (0.6.2).
                    val hint = if (own.taughtHold) null else AgentNotice.HoldToSendKeys
                    interaction.value = now.copy(input = TextFieldValue(""), refusal = hint, taughtHold = true)
                }
                else -> interaction.value = interaction.value.copy(refusal = outcome.notice())
            }
        }
    }

    /**
     * Presses what the user typed, key by key (phase 10, SPEC §2.5). There is no text field and no Send button: a
     * keystroke is a key name for `terminal.keys`, because text sent with `terminal.send` arrives with Enter and submits
     * the line (Pablo: "eu aperto, ele envia, como se eu apertasse + enter").
     *
     * @param text the characters the keyboard produced.
     */
    fun onType(text: String) {
        if (text.isEmpty()) return
        press(Keystrokes.of(text))
    }

    /**
     * Presses DELETE, as many times as the user erased.
     *
     * @param count how many characters back.
     */
    fun onBackspace(count: Int) {
        if (count <= 0) return
        press(Keystrokes.backspaces(count))
    }

    /**
     * Presses each character of the clipboard, so a paste lands at the cursor without submitting the line.
     *
     * @param text the clipboard's text.
     */
    fun onPaste(text: String) {
        if (text.isEmpty()) return
        press(Keystrokes.of(text))
    }

    /**
     * Presses [keystrokes] on the terminal. A blocked agent takes them forced, as Send did; what the core cannot press as
     * a key (an accent, an emoji) is reported instead of being sent as text, which would submit the line.
     *
     * @param keystrokes the keys to press.
     */
    private fun press(keystrokes: Keystrokes) {
        val current = state.value
        if (!current.canType) return
        if (keystrokes.dropped.isNotEmpty()) {
            interaction.value = interaction.value.copy(refusal = AgentNotice.NotTypable)
        }
        // Ctrl is latched by the bar and has no effect on the phone's own keyboard: Ctrl then `c` typed here is the
        // interrupt, the same combination the bar's ^C sends (0.5.7).
        val armed = interaction.value.ctrlArmed
        val names = if (armed && keystrokes.names.size == 1) {
            val only = keystrokes.names.single()
            val letter = only.removePrefix("shift+").lowercase()
            if (letter.length == 1 && letter.single() in 'a'..'z') {
                listOf("ctrl+$letter") // i18n-ok: the core's key name, not a word
            } else {
                keystrokes.names
            }
        } else {
            keystrokes.names
        }
        if (armed) interaction.value = interaction.value.copy(ctrlArmed = false)
        if (names.isEmpty()) return
        viewModelScope.launch {
            val outcome = handle.value?.keys(names) ?: TerminalAction.Offline
            if (outcome != TerminalAction.Done) {
                interaction.value = interaction.value.copy(refusal = outcome.notice())
            }
        }
    }

    /** Interrupt: Ctrl+C through `terminal.interrupt`. */
    fun onInterrupt() {
        interaction.value = interaction.value.copy(menuOpen = false, lastKey = SentKey("^C"))
        viewModelScope.launch {
            val outcome = handle.value?.interrupt() ?: TerminalAction.Offline
            interaction.value = interaction.value.copy(refusal = if (outcome == TerminalAction.Done) null else outcome.notice())
        }
    }

    /** Opens the overflow menu. */
    fun onOpenMenu() {
        interaction.value = interaction.value.copy(menuOpen = true)
    }

    /**
     * Closes the overflow menu. A dismissed sheet also drops the close question and the rename field: otherwise opening the
     * sheet again would show it stuck in that step.
     */
    fun onCloseMenu() {
        interaction.value = interaction.value.copy(menuOpen = false, confirmingClose = false, renaming = false)
    }

    /** Turns the menu into the rename field, with the current name in it (0.7.6). */
    fun onStartRename() {
        val current = interaction.value
        val draft = current.renameDraft.ifBlank { state.value.name }
        interaction.value = current.copy(renaming = true, renameDraft = draft, menuOpen = true)
    }

    /** @param text what the user typed as the new name. */
    fun onRenameDraft(text: String) {
        interaction.value = interaction.value.copy(renameDraft = text)
    }

    /**
     * Saves the typed name as the terminal's `whistl.name` label, the same action the Agents list uses (0.7.6).
     *
     * An empty name is refused here rather than sent to the core, and the header takes the new name at once because the list
     * and this screen read the same terminal.
     */
    fun onConfirmRename() {
        val name = interaction.value.renameDraft.trim()
        if (name.isEmpty()) return
        interaction.value = interaction.value.copy(renaming = false, menuOpen = false)
        viewModelScope.launch {
            val outcome = agentsRepo.act(machineId, address, AgentAction.Rename(name))
            if (outcome != ActionResult.Done) {
                interaction.value = interaction.value.copy(refusal = AgentNotice.Refused)
            }
        }
    }

    /**
     * Asks this agent's machine for the subscription limits, on the tap (0.7.6). Same call as the Agents list's menu, so the two
     * sheets cannot disagree.
     */
    fun onShowLimits() {
        val current = interaction.value
        if (current.limitsLoading) return
        interaction.value = current.copy(limitsLoading = true, limits = null)
        viewModelScope.launch {
            if (limits == null) {
                interaction.value = interaction.value.copy(limitsLoading = false, limits = LimitsResult.Failed("no limits on this build"))
                return@launch
            }
            limits.refresh(machineId)
            val answer = limits.reports().value[machineId]
            interaction.value = interaction.value.copy(limitsLoading = false, limits = answer)
        }
    }

    /**
     * Turns the action sheet into the close question. The sheet stays open: the question is the same one the Agents list
     * and the Space detail ask, in the same component.
     */
    fun onAskClose() {
        interaction.value = interaction.value.copy(confirmingClose = true)
    }

    /** Goes back from the close question to the actions. */
    fun onCancelClose() {
        interaction.value = interaction.value.copy(confirmingClose = false)
    }

    /** Confirmed: kills the terminal, removes it, and closes the screen. */
    fun onConfirmClose() {
        interaction.value = interaction.value.copy(confirmingClose = false, menuOpen = false)
        viewModelScope.launch {
            val outcome = handle.value?.stopAndRemove() ?: TerminalAction.Offline
            interaction.value = if (outcome == TerminalAction.Done || outcome == TerminalAction.Gone) {
                interaction.value.copy(closed = true)
            } else {
                interaction.value.copy(refusal = outcome.notice())
            }
        }
    }

    /**
     * Pins this agent, or unpins it, in the same store the Agents list and the Space detail write. A pin is local: nothing
     * is sent to the core, and the sheet closes as it does on the lists.
     */
    fun onTogglePin() {
        pins.toggle(pinKey)
        interaction.value = interaction.value.copy(menuOpen = false)
    }

    /** The screen closes: the terminal stops being read and the machine's lease is given back. */
    fun onLeave() {
        handle.value?.close()
    }

    /**
     * Reconnects the agent's machine without leaving the screen: the same immediate attempt the Machines card menu offers
     * (SPEC §2.5). The header offers it only while [canReconnect] holds.
     */
    fun onReconnect() {
        viewModelScope.launch {
            try {
                machines.reconnect(machineId)
            } catch (e: NoSuchElementException) {
                // The machine left the app while the screen was open: the terminal is gone, as when it cannot be opened.
                interaction.update { it.copy(unavailable = true) }
            }
        }
    }

    override fun onCleared() {
        onLeave()
    }

    /**
     * Sends what is in the field as keys, without Return (0.6.1). A TUI that waits for one key — the `m` after a leader
     * like Ctrl+X, a `y` in a prompt — needs the character at the cursor, not a submitted line, and the Send button alone
     * cannot express that.
     */
    fun onSendKeys() {
        val current = state.value
        if (!current.canType) return
        val own = interaction.value
        val text = own.input.text
        if (text.isEmpty()) return
        val keystrokes = Keystrokes.of(text)
        interaction.value = own.copy(input = TextFieldValue(""), ctrlArmed = false)
        if (keystrokes.dropped.isNotEmpty()) {
            interaction.value = interaction.value.copy(refusal = AgentNotice.NotTypable)
        }
        if (keystrokes.isEmpty) return
        sendKeys(keystrokes.names)
    }

    private fun sendKeys(names: List<String>) {
        viewModelScope.launch {
            val outcome = handle.value?.keys(names) ?: TerminalAction.Offline
            if (outcome != TerminalAction.Done) interaction.value = interaction.value.copy(refusal = outcome.notice())
        }
    }

    private fun build(
        output: TerminalScreen,
        sighting: Sighting,
        machineName: String,
        machineUser: String,
        fontSize: FontSize,
        keyBar: List<ShortcutKey>,
        own: Interaction,
        pinned: Boolean,
        machineState: ConnectionState,
    ): AgentUiState {
        // No connection (offline, or still connecting): the agent is shown as last seen, so its status and space stay (phase 7 r7).
        val offline = output.reach == TerminalReach.Offline && !own.unavailable
        val current = if (offline) sighting.now ?: sighting.lastSeen else sighting.now
        val terminal = current?.agent?.terminal
        val live = terminal?.state == TerminalState.Live
        // The label says offline unless the core reported the program finished: a missing or unlisted agent is never "finished".
        val reportedFinished = terminal?.state == TerminalState.Finished
        val derived = when {
            own.unavailable -> AgentNotice.Gone
            output.reach == TerminalReach.Offline -> AgentNotice.Offline
            output.reach == TerminalReach.Gone || terminal == null -> AgentNotice.Gone
            !live -> AgentNotice.Finished
            else -> null
        }
        val notice = derived ?: own.refusal
        val canType = derived == null && output.reach == TerminalReach.Connected
        val status = terminal?.status
        val input = own.input
        return AgentUiState(
            name = current?.displayName ?: address,
            address = address,
            machineId = machineId,
            machineName = machineName,
            user = machineUser,
            spaceName = current?.space?.name,
            statusLabel = if (offline && !reportedFinished) {
                AgentStatusLabel.Offline
            } else {
                statusLabel(status, live, terminal != null)
            },
            status = status,
            live = live,
            frame = output.frame,
            reach = output.reach,
            canReconnect = !own.unavailable && machineState.canReconnect(),
            input = input,
            ctrlArmed = own.ctrlArmed,
            keyBar = keyBar,
            lastKey = own.lastKey,
            notice = notice,
            canType = canType,
            canSend = canType && input.text.isNotBlank(),
            blockedAgent = status == AgentStatus.Blocked && live,
            menuOpen = own.menuOpen,
            confirmingClose = own.confirmingClose,
            closed = own.closed,
            pinned = pinned,
            fontSize = fontSize,
            renaming = own.renaming,
            renameDraft = own.renameDraft,
            limits = own.limits,
            limitsLoading = own.limitsLoading,
        )
    }

    private fun statusLabel(status: AgentStatus?, live: Boolean, present: Boolean): AgentStatusLabel = when {
        !present || !live -> AgentStatusLabel.Finished
        status == AgentStatus.Working -> AgentStatusLabel.Working
        status == AgentStatus.Blocked -> AgentStatusLabel.NeedsYou
        status == AgentStatus.Done -> AgentStatusLabel.Done
        status == AgentStatus.Idle -> AgentStatusLabel.Idle
        status == AgentStatus.Error -> AgentStatusLabel.Error
        else -> AgentStatusLabel.Plain
    }

    private fun placeholder(): AgentUiState = AgentUiState(
        name = address,
        address = address,
        machineId = machineId,
        machineName = "",
        user = "",
        spaceName = null,
        statusLabel = AgentStatusLabel.Offline,
        status = null,
        live = false,
        frame = TerminalFrame.empty(DEFAULT_COLS, DEFAULT_ROWS),
        reach = TerminalReach.Offline,
        canReconnect = false,
        input = TextFieldValue(""),
        ctrlArmed = false,
        keyBar = emptyList(),
        lastKey = null,
        notice = AgentNotice.Offline,
        canType = false,
        canSend = false,
        blockedAgent = false,
        menuOpen = false,
        confirmingClose = false,
        closed = false,
        pinned = false,
        fontSize = FontSize.Medium,
    )
}

/** The size a screen starts at, before its pane has been measured. */
private const val DEFAULT_COLS: Int = 80
private const val DEFAULT_ROWS: Int = 24

/** The notice a refused action shows. Done has none. */
private fun TerminalAction.notice(): AgentNotice? = when (this) {
    TerminalAction.Done -> null
    TerminalAction.Offline -> AgentNotice.Offline
    TerminalAction.Gone -> AgentNotice.Gone
    TerminalAction.Blocked -> AgentNotice.Blocked
    TerminalAction.Refused -> AgentNotice.Refused
}

/**
 * The field with [text] inserted at the cursor, which moves after it. A selection is replaced.
 *
 * @param text the text to insert.
 * @return the new field value.
 */
internal fun TextFieldValue.insertAtCursor(text: String): TextFieldValue {
    val start = selection.min.coerceIn(0, this.text.length)
    val end = selection.max.coerceIn(start, this.text.length)
    val inserted = this.text.substring(0, start) + text + this.text.substring(end)
    return copy(text = inserted, selection = TextRange(start + text.length))
}
