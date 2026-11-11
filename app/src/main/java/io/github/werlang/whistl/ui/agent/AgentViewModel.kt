package io.github.werlang.whistl.ui.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.SettingsRepository
import io.github.werlang.whistl.data.TerminalAction
import io.github.werlang.whistl.data.TerminalHandle
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.TerminalScreen
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.KeyAction
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.domain.TerminalFrame
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.domain.action
import io.github.werlang.whistl.domain.ctrlCombination
import io.github.werlang.whistl.domain.letterCombination
import io.github.werlang.whistl.ui.agents.displayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * @property machineName the machine's name.
 * @property spaceName the space's name, or `null` for "No space".
 * @property statusLabel the label next to the status dot.
 * @property status the raw status, for the dot's colour; `null` for a plain program.
 * @property frame the terminal as the emulator holds it: history and the screen rows, with the cursor.
 * @property reach whether the output is current.
 * @property input the text field, with its cursor.
 * @property ctrlArmed whether the sticky Ctrl is armed for the next key.
 * @property keyBar the shortcut keys, in the order the Settings screen holds them.
 * @property lastKey the last key sent, for a short hint: `null` when none.
 * @property notice why input is disabled, or the last refusal.
 * @property canType whether the text field and the key bar take input.
 * @property canSend whether Send is enabled: input allowed and the field not empty.
 * @property blockedAgent whether the agent is blocked, so the screen explains that Send forces the text.
 * @property menuOpen whether the overflow menu sheet is open.
 * @property confirmStop whether the stop-and-remove confirmation is open.
 * @property closed `true` once the agent was stopped and removed: the screen navigates back.
 * @property fontSize the terminal font size from Settings.
 */
data class AgentUiState(
    val name: String,
    val address: String,
    val machineName: String,
    val spaceName: String?,
    val statusLabel: AgentStatusLabel,
    val status: AgentStatus?,
    val frame: TerminalFrame,
    val reach: TerminalReach,
    val input: TextFieldValue,
    val ctrlArmed: Boolean,
    val keyBar: List<ShortcutKey>,
    val lastKey: SentKey?,
    val notice: AgentNotice?,
    val canType: Boolean,
    val canSend: Boolean,
    val blockedAgent: Boolean,
    val menuOpen: Boolean,
    val confirmStop: Boolean,
    val closed: Boolean,
    val fontSize: FontSize,
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
    val confirmStop: Boolean = false,
    val closed: Boolean = false,
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
 * - Interrupt sends Ctrl+C. Stop and remove asks first, then kills and removes the terminal, and the screen closes.
 * - Input is disabled while the machine is offline, the terminal is gone, or the program has finished.
 * - While the machine has no connection (offline or connecting), the agent is shown as it was last seen: its status and its
 *   space stay, and its label says offline. Only a program the core reported finished says finished (phase 7 r7).
 *
 * @param terminals opens the terminal.
 * @param agents the agent's status and state.
 * @param machines the machine's name.
 * @param settings the font size and the shortcut bar.
 * @param machineId the machine the agent runs on.
 * @param address the terminal address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(
    private val terminals: TerminalRepository,
    agents: AgentsRepository,
    machines: MachinesRepository,
    settings: SettingsRepository,
    private val machineId: String,
    private val address: String,
) : ViewModel() {
    private val handle = MutableStateFlow<TerminalHandle?>(null)
    private val interaction = MutableStateFlow(Interaction())

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
        interaction,
    ) { output, sighting, machineList, prefs, own ->
        build(output, sighting, machineList.firstOrNull { it.id == machineId }?.name ?: machineId, prefs.fontSize, prefs.shortcutBar, own)
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
        val combination = if (own.ctrlArmed && state.value.canType && own.input.text.isEmpty() && newValue.text.length == 1) {
            letterCombination(newValue.text[0])
        } else {
            null
        }
        if (combination == null) {
            interaction.value = own.copy(input = newValue)
        } else {
            val letter = newValue.text[0].uppercaseChar().toString()
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
                TerminalAction.Done -> interaction.value = interaction.value.copy(input = TextFieldValue(""), refusal = null)
                else -> interaction.value = interaction.value.copy(refusal = outcome.notice())
            }
        }
    }

    /**
     * Sends what the user typed straight to the terminal (phase 9, SPEC §2.5). There is no text field and no Send
     * button any more: a keystroke is a keystroke, and a blocked agent receives it forced, as Send did.
     *
     * @param text the characters the keyboard produced.
     */
    fun onType(text: String) {
        if (text.isEmpty()) return
        write(text)
    }

    /**
     * Erases characters the user deleted while typing. The terminal owns the line, so a deletion on the phone has to
     * travel as the DELETE the terminal understands.
     *
     * @param count how many characters back.
     */
    fun onBackspace(count: Int) {
        if (count <= 0) return
        write("\u007f".repeat(count))
    }

    /**
     * @param text the clipboard's text, sent as it is at the cursor.
     */
    fun onPaste(text: String) {
        if (text.isEmpty()) return
        write(text)
    }

    private fun write(text: String) {
        val current = state.value
        // Input is gated by whether the terminal can take typing, not by whether there is text to send: there is no field.
        if (!current.canType) return
        val force = current.status == AgentStatus.Blocked
        viewModelScope.launch {
            val outcome = handle.value?.send(text, force) ?: TerminalAction.Offline
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

    /** Closes the overflow menu. */
    fun onCloseMenu() {
        interaction.value = interaction.value.copy(menuOpen = false)
    }

    /** Asks for confirmation before stopping and removing the agent. */
    fun onAskStop() {
        interaction.value = interaction.value.copy(menuOpen = false, confirmStop = true)
    }

    /** Cancels the stop-and-remove confirmation. */
    fun onCancelStop() {
        interaction.value = interaction.value.copy(confirmStop = false)
    }

    /** Confirmed: kills the terminal, removes it, and closes the screen. */
    fun onConfirmStop() {
        interaction.value = interaction.value.copy(confirmStop = false)
        viewModelScope.launch {
            val outcome = handle.value?.stopAndRemove() ?: TerminalAction.Offline
            interaction.value = if (outcome == TerminalAction.Done || outcome == TerminalAction.Gone) {
                interaction.value.copy(closed = true)
            } else {
                interaction.value.copy(refusal = outcome.notice())
            }
        }
    }

    /** The screen closes: the terminal stops being read and the machine's lease is given back. */
    fun onLeave() {
        handle.value?.close()
    }

    override fun onCleared() {
        onLeave()
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
        fontSize: FontSize,
        keyBar: List<ShortcutKey>,
        own: Interaction,
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
            name = terminal?.let { displayName(it.name, it.argv, it.address) } ?: address,
            address = address,
            machineName = machineName,
            spaceName = current?.space?.name,
            statusLabel = if (offline && !reportedFinished) {
                AgentStatusLabel.Offline
            } else {
                statusLabel(status, live, terminal != null)
            },
            status = status,
            frame = output.frame,
            reach = output.reach,
            input = input,
            ctrlArmed = own.ctrlArmed,
            keyBar = keyBar,
            lastKey = own.lastKey,
            notice = notice,
            canType = canType,
            canSend = canType && input.text.isNotBlank(),
            blockedAgent = status == AgentStatus.Blocked && live,
            menuOpen = own.menuOpen,
            confirmStop = own.confirmStop,
            closed = own.closed,
            fontSize = fontSize,
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
        machineName = "",
        spaceName = null,
        statusLabel = AgentStatusLabel.Offline,
        status = null,
        frame = TerminalFrame.empty(DEFAULT_COLS, DEFAULT_ROWS),
        reach = TerminalReach.Offline,
        input = TextFieldValue(""),
        ctrlArmed = false,
        keyBar = emptyList(),
        lastKey = null,
        notice = AgentNotice.Offline,
        canType = false,
        canSend = false,
        blockedAgent = false,
        menuOpen = false,
        confirmStop = false,
        closed = false,
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
