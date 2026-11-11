package io.github.werlang.whistl.ui.machines

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.StatusLabel
import io.github.werlang.whistl.ui.theme.AppMark
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the Machines list, so a test can find it. */
const val MACHINES_LIST_TAG: String = "machines-list"

/**
 * The test tag of one machine's card: `machine-card-<id>`.
 *
 * @param id the machine id.
 * @return the tag.
 */
fun machineCardTag(id: String): String = "machine-card-$id" // i18n-ok: test tag, never shown

/**
 * The Machines list (SPEC §2.1): one card per machine, or the empty state. Each card's menu button opens its menu.
 *
 * @param state what the screen shows.
 * @param onMenu called with the id of the machine whose menu button was tapped.
 * @param modifier the modifier for the screen.
 */
@Composable
fun MachinesScreen(
    state: MachinesUiState,
    onMenu: (String) -> Unit,
    onOpenSpaces: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
            AppMark()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.machines_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        state.problem?.let {
            Text(
                text = stringResource(R.string.machines_problem_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        if (state.cards.isEmpty()) {
            EmptyMachines(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            LazyColumn(
                modifier = Modifier.testTag(MACHINES_LIST_TAG),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.cards, key = { it.id }) { card ->
                    MachineCardView(card = card, onMenu = { onMenu(card.id) }, onOpen = { onOpenSpaces(card.id) })
                }
            }
        }
    }
}

/** The empty state: a bordered box with a title and a hint (SPEC §2.1). */
@Composable
private fun EmptyMachines(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, LocalWhistlTokens.current.field), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.machines_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.machines_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalWhistlTokens.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One machine card: its name, status, address, agents and version, and the installed agents. The menu button is 48 dp wide.
 *
 * @param card the machine.
 * @param onMenu called when the menu button is tapped.
 */
@Composable
private fun MachineCardView(card: MachineCard, onMenu: () -> Unit, onOpen: () -> Unit) {
    val tokens = LocalWhistlTokens.current
    val openDescription = stringResource(R.string.machines_open_spaces, card.name)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(machineCardTag(card.id))
            // The whole card opens that machine's spaces; the menu button keeps its own touch target.
            .clickable(onClick = onOpen)
            .semantics { contentDescription = openDescription },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLabel(label = stringResource(cardStatusLabelRes(card)), color = cardStatusColor(card.status))
                // Two lines, not one: the port is the part that tells two machines apart, so it must not be cut off.
                Text(
                    text = card.address.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = WhistlMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = card.agentCount
                val version = card.version
                if (count != null && version != null) {
                    Text(
                        text = stringResource(
                            R.string.machine_meta,
                            pluralStringResource(R.plurals.machine_agents, count, count),
                            version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.muted,
                    )
                }
                card.installed?.let { names ->
                    Text(
                        text = if (names.isEmpty()) {
                            stringResource(R.string.machine_none_installed)
                        } else {
                            stringResource(R.string.machine_installed, names.joinToString(stringResource(R.string.separator_list)))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(width = MinTouchTarget, height = 52.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.machine_options),
                    tint = tokens.muted,
                )
            }
        }
    }
}

/**
 * The status label of a card: its connection, or for an error the reason the user has to act on.
 *
 * @param card the card.
 * @return the string resource.
 */
private fun cardStatusLabelRes(card: MachineCard): Int = when (card.status) {
    MachineStatus.Connected -> R.string.machine_status_connected
    MachineStatus.Connecting -> R.string.machine_status_connecting
    MachineStatus.Offline -> R.string.machine_status_offline
    MachineStatus.Error -> when (card.issue) {
        MachineIssue.LoginRefused -> R.string.machine_issue_login_refused
        MachineIssue.UnsupportedCore -> R.string.machine_issue_unsupported_core
        MachineIssue.HostKeyToConfirm -> R.string.machine_issue_host_key
        MachineIssue.HostKeyChanged -> R.string.machine_issue_host_key_changed
        MachineIssue.ConnectionRefused -> R.string.machine_issue_connection_refused
        MachineIssue.Internal -> R.string.machine_issue_internal
        MachineIssue.CoreMissing -> R.string.machine_issue_core_missing
        null -> R.string.status_error
    }
}

/**
 * The dot colour of a card's status: connected is done, connecting is working, offline is idle, an error is the error colour.
 * The label always says the status too, so the colour is never the only signal (SPEC §3).
 *
 * @param status the card's status.
 * @return the colour.
 */
@Composable
private fun cardStatusColor(status: MachineStatus): Color {
    val tokens = LocalWhistlTokens.current
    return when (status) {
        MachineStatus.Connected -> tokens.done
        MachineStatus.Connecting -> tokens.working
        MachineStatus.Offline -> tokens.idle
        MachineStatus.Error -> MaterialTheme.colorScheme.error
    }
}
