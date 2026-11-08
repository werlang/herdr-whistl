package io.github.werlang.whistl.ui.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.ui.agentLabelRes
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.StatusLabel
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.unresolvedTextRes
import io.github.werlang.whistl.ui.statusColor
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the Space detail screen. */
const val SPACE_DETAIL_TAG: String = "space-detail"

/** The test tag of the Space detail's Back button. */
const val SPACE_DETAIL_BACK_TAG: String = "space-detail-back"

/** The test tag of the New agent button. */
const val NEW_AGENT_TAG: String = "new-agent"

/** The test tag of the picker sheet. */
const val PICKER_SHEET_TAG: String = "picker-sheet"

/** The test tag of the picker's Terminal row. */
const val PICKER_TERMINAL_TAG: String = "picker-terminal"

/**
 * The test tag of one agent row of the Space detail: `detail-agent-<address>`.
 *
 * @param address the terminal's address.
 * @return the tag.
 */
fun detailAgentTag(address: String): String = "detail-agent-$address"

/**
 * The test tag of one installed agent in the picker: `picker-agent-<id>`.
 *
 * @param id the agent's id.
 * @return the tag.
 */
fun pickerAgentTag(id: String): String = "picker-agent-$id"

/**
 * The Space detail destination: the space's header, its agents, the New agent button, and the picker it opens. An agent that
 * starts opens at once; a tapped agent row opens its Agent screen.
 *
 * @param model the Space detail view model, for this space.
 * @param onOpenAgent called with an agent's address to open its Agent screen.
 * @param onBack called to leave the screen.
 * @param modifier the modifier for the screen.
 */
@Composable
fun SpaceDetailRoute(
    model: SpaceDetailViewModel,
    onOpenAgent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    LaunchedEffect(state.opened) {
        state.opened?.let { address ->
            model.consumeOpened()
            onOpenAgent(address)
        }
    }
    SpaceDetailScreen(
        state = state,
        onOpenAgent = onOpenAgent,
        onNewAgent = model::openPicker,
        onBack = onBack,
        modifier = modifier,
    )
    state.picker?.let { picker ->
        PickerSheet(
            picker = picker,
            machineName = state.machineName,
            onChoose = model::start,
            onDismiss = model::closePicker,
        )
    }
}

/**
 * The Space detail's screen, without its picker.
 *
 * @param state what the screen shows.
 * @param onOpenAgent called with an agent's address.
 * @param onNewAgent called when New agent is tapped; it does nothing while New agent is off.
 * @param onBack called to leave.
 * @param modifier the modifier for the screen.
 */
@Composable
fun SpaceDetailScreen(
    state: SpaceDetailUiState,
    onOpenAgent: (String) -> Unit,
    onNewAgent: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().testTag(SPACE_DETAIL_TAG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SPACE_DETAIL_BACK_TAG)) {
                    Text(stringResource(R.string.common_back))
                }
            }
            if (state.missing) {
                Text(
                    text = stringResource(R.string.space_detail_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalWhistlTokens.current.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                return@Box
            }
            Header(state)
            if (state.agents.isEmpty()) {
                EmptyAgents(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.agents, key = { it.address }) { agent ->
                    AgentRowView(agent = agent, onOpen = { onOpenAgent(agent.address) })
                }
            }
        }
        NewAgentButton(
            ui = state.newAgent,
            machineName = state.machineName,
            onClick = onNewAgent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/**
 * The header: the space's name, `machine:folder` in monospace, the folder's state, and the machine's offline notice.
 *
 * @param state the screen's state.
 */
@Composable
private fun Header(state: SpaceDetailUiState) {
    val tokens = LocalWhistlTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = state.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.space_detail_header, state.machineName, state.folder),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = WhistlMono,
            color = tokens.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (val folder = state.folderState) {
            FolderState.Resolved -> Unit
            FolderState.NotChecked -> Text(
                text = stringResource(R.string.space_folder_not_checked),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted,
            )

            is FolderState.Unavailable -> Text(
                text = stringResource(unresolvedTextRes(folder.reason)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.machineOffline) {
            Text(
                text = stringResource(R.string.space_detail_offline, state.machineName),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.muted,
            )
        }
    }
}

/** The empty state of a space with no agents (SPEC §2.3). */
@Composable
private fun EmptyAgents(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, LocalWhistlTokens.current.field), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.space_detail_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.space_detail_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalWhistlTokens.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One agent of the space: its name, its address in monospace, and its status on a line of its own.
 *
 * @param agent the agent.
 * @param onOpen called when the row is tapped.
 */
@Composable
private fun AgentRowView(agent: DetailAgent, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(detailAgentTag(agent.address))
            .clickable(role = Role.Button, onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = agent.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = agent.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = WhistlMono,
                color = LocalWhistlTokens.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLabel(
                label = stringResource(agentLabelRes(live = agent.live, waiting = agent.waiting, status = agent.status)),
                color = statusColor(agent.status, agent.live),
            )
        }
    }
}

/**
 * The New agent button. It is off while the machine is offline or the folder is not resolved; off, it does nothing, and its
 * description says why.
 *
 * @param ui the button's state.
 * @param machineName the machine's name, for the offline reason.
 * @param onClick called when it is on and tapped.
 * @param modifier the modifier for the button.
 */
@Composable
private fun NewAgentButton(ui: NewAgentUi, machineName: String, onClick: () -> Unit, modifier: Modifier) {
    val description = when (val block = ui.block) {
        null -> stringResource(R.string.new_agent)
        NewAgentBlock.MachineOffline -> stringResource(R.string.new_agent_off_machine, machineName)
        is NewAgentBlock.Folder -> when (block.state) {
            FolderState.NotChecked -> stringResource(R.string.new_agent_off_folder_unchecked)
            else -> stringResource(R.string.new_agent_off_folder)
        }
    }
    val tokens = LocalWhistlTokens.current
    FloatingActionButton(
        onClick = { if (ui.enabled) onClick() },
        containerColor = if (ui.enabled) MaterialTheme.colorScheme.primaryContainer else tokens.field,
        contentColor = if (ui.enabled) MaterialTheme.colorScheme.onPrimaryContainer else tokens.muted,
        modifier = modifier.testTag(NEW_AGENT_TAG).semantics {
            contentDescription = description
            if (!ui.enabled) disabled()
        },
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
    }
}

/**
 * The picker: the agents installed on the machine, then Terminal (the machine's shell). It has no text box (SPEC §2.3).
 *
 * @param picker the picker's state.
 * @param machineName the machine's name, for the section title.
 * @param onChoose called with the choice the user made.
 * @param onDismiss called when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    picker: PickerUi,
    machineName: String,
    onChoose: (StartChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(PICKER_SHEET_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.new_agent),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            val installed = picker.choices.filterIsInstance<StartChoice.Known>()
            Text(
                text = if (installed.isEmpty()) {
                    stringResource(R.string.machine_none_installed)
                } else {
                    stringResource(R.string.picker_installed, machineName)
                },
                style = MaterialTheme.typography.labelLarge,
                color = LocalWhistlTokens.current.muted,
            )
            picker.choices.forEach { choice ->
                when (choice) {
                    is StartChoice.Known -> ChoiceRow(
                        title = choice.agent.displayName,
                        subtitle = null,
                        tag = pickerAgentTag(choice.agent.id),
                        enabled = !picker.starting,
                        onClick = { onChoose(choice) },
                    )

                    StartChoice.Terminal -> ChoiceRow(
                        title = stringResource(R.string.picker_terminal),
                        subtitle = stringResource(R.string.picker_terminal_sub),
                        tag = PICKER_TERMINAL_TAG,
                        enabled = !picker.starting,
                        onClick = { onChoose(StartChoice.Terminal) },
                    )
                }
            }
            if (picker.starting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.picker_starting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalWhistlTokens.current.muted,
                    )
                }
            }
            picker.problem?.let { problem ->
                Text(
                    text = stringResource(startProblemRes(problem)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * One choice of the picker: its title, an optional line under it, and a 48 dp touch target.
 *
 * @param title the choice's title.
 * @param subtitle the line under the title, or `null`.
 * @param tag the test tag.
 * @param enabled whether it can be tapped.
 * @param onClick called when tapped.
 */
@Composable
private fun ChoiceRow(title: String, subtitle: String?, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalWhistlTokens.current.muted,
        )
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LocalWhistlTokens.current.muted)
        }
    }
}

/**
 * The message for a start that did not happen.
 *
 * @param problem the problem.
 * @return the string resource.
 */
fun startProblemRes(problem: StartProblem): Int = when (problem) {
    StartProblem.CouldNotStart -> R.string.start_could_not
    StartProblem.MachineOffline -> R.string.start_offline
    StartProblem.UnknownSpace -> R.string.start_unknown_space
    is StartProblem.FolderUnavailable -> problem.reason?.let(::unresolvedTextRes) ?: R.string.space_folder_not_checked
}
