package io.github.werlang.whistl.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.components.StatusLabel
import io.github.werlang.whistl.ui.keyNameRes
import io.github.werlang.whistl.ui.statusColor
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono
import io.github.werlang.whistl.ui.theme.WhistlTerminalText

/** The test tag of the Agent screen, so a navigation test can see that it opened. */
const val AGENT_SCREEN_TAG: String = "agent-screen"

/** The test tag of the terminal output: its lines are separate semantics nodes inside it (phase 7 r6, row 13). */
const val AGENT_OUTPUT_TAG: String = "agent-output"

/** The test tag of the Agent screen's options sheet (Interrupt, Stop and remove). */
const val AGENT_MENU_SHEET_TAG: String = "agent-menu-sheet"

/**
 * The Agent screen (SPEC §2.5): a full-height terminal, the status bar, the shortcut keys in Settings order, the text field and
 * Send. The overflow menu holds Interrupt and Stop and remove; Stop and remove asks first, then closes the screen.
 *
 * @param viewModel the screen's state and events.
 * @param onBack called when the screen closes: the back arrow, or the agent was stopped and removed.
 * @param modifier the modifier for the screen.
 */
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }
    AgentContent(
        state = state,
        onBack = onBack,
        onKey = viewModel::onKey,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onOpenMenu = viewModel::onOpenMenu,
        onCloseMenu = viewModel::onCloseMenu,
        onInterrupt = viewModel::onInterrupt,
        onAskStop = viewModel::onAskStop,
        onCancelStop = viewModel::onCancelStop,
        onConfirmStop = viewModel::onConfirmStop,
        modifier = modifier,
    )
}

/** The screen without its view model: what it shows, and the events it reports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentContent(
    state: AgentUiState,
    onBack: () -> Unit,
    onKey: (ShortcutKey) -> Unit,
    onInputChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onInterrupt: () -> Unit,
    onAskStop: () -> Unit,
    onCancelStop: () -> Unit,
    onConfirmStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag(AGENT_SCREEN_TAG)) {
        AgentHeader(state = state, onBack = onBack, onOptions = onOpenMenu)
        TerminalOutput(state = state, modifier = Modifier.weight(1f))
        StatusBar(state = state)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyBar(state = state, onKey = onKey)
            HintLine(state = state)
            NoticeLine(state = state)
            BlockedHint(state = state)
            InputRow(state = state, onInputChange = onInputChange, onSend = onSend)
        }
    }
    if (state.menuOpen) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest =onCloseMenu, modifier = Modifier.testTag(AGENT_MENU_SHEET_TAG)) {
            SheetAction(text = stringResource(R.string.agent_interrupt), onClick = onInterrupt)
            SheetAction(text = stringResource(R.string.agent_stop), danger = true, onClick = onAskStop)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (state.confirmStop) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest =onCancelStop) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.agent_stop_title, state.name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.agent_stop_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalWhistlTokens.current.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancelStop, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onConfirmStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.agent_stop_confirm))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The header: back, the name and address, the status with its dot, and the options button. */
@Composable
private fun AgentHeader(state: AgentUiState, onBack: () -> Unit, onOptions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = WhistlMono,
                    color = LocalWhistlTokens.current.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusLabel(
                label = stringResource(statusLabelRes(state.statusLabel)),
                color = when (state.statusLabel) {
                    AgentStatusLabel.Finished, AgentStatusLabel.Offline -> LocalWhistlTokens.current.idle
                    else -> statusColor(state.status)
                },
            )
        }
        IconButton(onClick = onOptions, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.agent_options))
        }
    }
}

/** The terminal: the output in monospace on the always-dark terminal colour, scrolled to its last line. */
@Composable
private fun TerminalOutput(state: AgentUiState, modifier: Modifier) {
    val scroll = rememberScrollState()
    val description = stringResource(R.string.agent_terminal_description)
    LaunchedEffect(state.screenText) { scroll.scrollTo(scroll.maxValue) }
    val size = fontSizeFor(state.fontSize)
    // One text per line: a screen reader moves line by line, not through one node holding the whole buffer. The container keeps
    // its description and does not replace the lines (phase 7 r6, row 13). The look is the same as one text block.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalWhistlTokens.current.terminal)
            .verticalScroll(scroll)
            .padding(12.dp)
            .testTag(AGENT_OUTPUT_TAG)
            .semantics { contentDescription = description },
    ) {
        state.screenText.split('\n').forEach { line ->
            Text(
                text = line,
                fontFamily = WhistlMono,
                fontSize = size,
                lineHeight = size * 1.4f,
                color = WhistlTerminalText,
            )
        }
    }
}

/** The one-line status bar, on the accent colour: `[machine] space · agent` and the status. */
@Composable
private fun StatusBar(state: AgentUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.agent_bar_place,
                state.machineName,
                state.spaceName ?: stringResource(R.string.common_no_space),
                state.name,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(statusLabelRes(state.statusLabel)),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/** The shortcut keys in the order Settings holds them. Ctrl shows armed; a disabled bar does nothing when tapped. */
@Composable
private fun KeyBar(state: AgentUiState, onKey: (ShortcutKey) -> Unit) {
    val tokens = LocalWhistlTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.keyBar.forEach { key ->
            val armed = key == ShortcutKey.CTRL && state.ctrlArmed
            val description = if (armed) {
                stringResource(R.string.agent_ctrl_armed_description)
            } else {
                stringResource(keyNameRes(key))
            }
            Button(
                onClick = { onKey(key) },
                enabled = state.canType,
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (armed) MaterialTheme.colorScheme.primary else tokens.field,
                    contentColor = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .widthIn(min = MinTouchTarget)
                    .semantics { contentDescription = description },
            ) {
                Text(text = key.label, fontFamily = WhistlMono, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** What the screen is doing: the Ctrl hint, the last key sent, or nothing. */
@Composable
private fun HintLine(state: AgentUiState) {
    val lastKey = state.lastKey
    val text = when {
        state.ctrlArmed -> stringResource(R.string.agent_ctrl_on)
        lastKey != null -> stringResource(R.string.agent_key_sent, sentKeyText(lastKey))
        else -> null
    }
    if (text != null) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = LocalWhistlTokens.current.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * The key as the hint shows it: its name, with "Ctrl+" in front when it was sent with Ctrl.
 *
 * @param key the last key sent.
 * @return the text the hint line shows for the key.
 */
@Composable
private fun sentKeyText(key: SentKey): String =
    if (key.ctrl) stringResource(R.string.agent_key_ctrl, key.label) else key.label

/**
 * The notice line: why input is off, or the last refusal (red), or, for a blocked agent, what Send does. Nothing shows when the
 * terminal is ready.
 */
@Composable
private fun NoticeLine(state: AgentUiState) {
    val notice = state.notice ?: return
    val text = when (notice) {
        AgentNotice.Offline -> stringResource(R.string.agent_notice_offline, state.machineName)
        AgentNotice.Gone -> stringResource(R.string.agent_notice_gone)
        AgentNotice.Finished -> stringResource(R.string.agent_notice_finished)
        AgentNotice.Blocked -> stringResource(R.string.agent_notice_blocked)
        AgentNotice.Refused -> stringResource(R.string.agent_notice_refused)
    }
    val color = if (notice == AgentNotice.Blocked || notice == AgentNotice.Refused) {
        MaterialTheme.colorScheme.error
    } else {
        LocalWhistlTokens.current.muted
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 4.dp))
}

/** For a blocked agent, while nothing else is noticed: what Send does (it goes in forced). */
@Composable
private fun BlockedHint(state: AgentUiState) {
    if (state.notice == null && state.blockedAgent) {
        Text(
            text = stringResource(R.string.agent_hint_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = LocalWhistlTokens.current.blocked,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The text field and Send. Input is off while the terminal cannot take it; Send needs text. */
@Composable
private fun InputRow(
    state: AgentUiState,
    onInputChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    val inputDescription = stringResource(R.string.agent_input_description)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            enabled = state.canType,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.agent_input_placeholder, state.name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = inputDescription },
        )
        IconButton(
            onClick = onSend,
            enabled = state.canSend,
            modifier = Modifier
                .size(MinTouchTarget)
                .border(1.dp, LocalWhistlTokens.current.field, RoundedCornerShape(12.dp))
                .background(
                    if (state.canSend) MaterialTheme.colorScheme.primary else LocalWhistlTokens.current.field,
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.agent_send),
                tint = if (state.canSend) MaterialTheme.colorScheme.onPrimary else LocalWhistlTokens.current.muted,
            )
        }
    }
}

/** One row of the overflow menu sheet; a danger row is red. Touch target: the whole row, at least 48 dp. */
@Composable
private fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The terminal's font size in sp (SPEC §2.6): small 11, medium 12.5, large 14.5, as the prototype sets them. */
fun fontSizeFor(size: FontSize): TextUnit = when (size) {
    FontSize.Small -> 11.sp
    FontSize.Medium -> 12.5.sp
    FontSize.Large -> 14.5.sp
}

/** The label resource for a status-bar label. */
private fun statusLabelRes(label: AgentStatusLabel): Int = when (label) {
    AgentStatusLabel.Working -> R.string.status_working
    AgentStatusLabel.NeedsYou -> R.string.status_needs_you
    AgentStatusLabel.Done -> R.string.status_done
    AgentStatusLabel.Idle -> R.string.status_idle
    AgentStatusLabel.Error -> R.string.status_error
    AgentStatusLabel.Plain -> R.string.status_plain
    AgentStatusLabel.Finished -> R.string.status_finished
    AgentStatusLabel.Offline -> R.string.machine_status_offline
}
