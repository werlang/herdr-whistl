package io.github.werlang.whistl.ui.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.agentLabelRes
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.statusColor
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the Spaces list. */
const val SPACES_LIST_TAG: String = "spaces-list"

/**
 * The test tag of one space row: `space-row-<machine>-<space>`.
 *
 * @param machineId the machine.
 * @param spaceId the space.
 * @return the tag.
 */
fun spaceRowTag(machineId: String, spaceId: String): String = "space-row-$machineId-$spaceId"

/**
 * The Spaces list (SPEC §2.2): one group per machine, pinned first and then the user's order. A row shows its name, one dot per
 * agent (each named in words), the waiting badge, `N agents · folder`, and the folder's state. An offline machine's group says
 * so, and its rows are dimmed; they still open.
 *
 * @param state the groups and whether there is any space.
 * @param onOpen called with the machine and the space when a row is tapped.
 * @param onMenu called with the machine and the space when a row's options button is tapped.
 * @param modifier the modifier for the screen.
 */
@Composable
fun SpacesScreen(
    state: SpacesUiState,
    onOpen: (machineId: String, spaceId: String) -> Unit,
    onMenu: (machineId: String, spaceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.destination_spaces),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.isEmpty) {
            EmptySpaces(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LazyColumn(
            modifier = Modifier.testTag(SPACES_LIST_TAG),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.groups, key = { it.machineId }) { group ->
                GroupHeader(group)
                group.rows.forEach { row ->
                    SpaceRowView(row = row, onOpen = { onOpen(row.machineId, row.spaceId) }, onMenu = { onMenu(row.machineId, row.spaceId) })
                }
            }
        }
    }
}

/** The empty state: a bordered box with the title and the hint (SPEC §2.2). */
@Composable
private fun EmptySpaces(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, LocalWhistlTokens.current.field), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.spaces_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.spaces_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalWhistlTokens.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A group's header: the machine's name and address, and "offline" when it is not connected.
 *
 * @param group the group.
 */
@Composable
private fun GroupHeader(group: SpaceGroup) {
    val muted = LocalWhistlTokens.current.muted
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.offline) {
                Text(
                    text = stringResource(R.string.machine_status_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        if (group.address.isNotEmpty()) {
            Text(
                text = group.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = WhistlMono,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One space row. The whole row opens the Space detail; its options button, 48 dp wide, opens the row menu. A dimmed row (its
 * machine is offline) is drawn faint but still tappable.
 *
 * @param row the row.
 * @param onOpen called when the row is tapped.
 * @param onMenu called when the options button is tapped.
 */
@Composable
private fun SpaceRowView(row: SpaceRow, onOpen: () -> Unit, onMenu: () -> Unit) {
    val tokens = LocalWhistlTokens.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(spaceRowTag(row.machineId, row.spaceId))
            .clickable(role = Role.Button, onClick = onOpen)
            .then(if (row.dimmed) Modifier.alpha(0.5f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.pinned) {
                        Text(
                            text = stringResource(R.string.spaces_pinned),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.muted,
                        )
                    }
                    if (row.waitingCount > 0) {
                        WaitingBadge()
                    }
                }
                AgentDots(row.agents)
                Text(
                    text = stringResource(
                        R.string.spaces_summary,
                        pluralStringResource(R.plurals.space_agents_count, row.agents.size, row.agents.size),
                        row.folder,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = WhistlMono,
                    color = tokens.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FolderLine(row.folderState)
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(width = MinTouchTarget, height = 52.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.space_options),
                    tint = tokens.muted,
                )
            }
        }
    }
}

/**
 * One dot per agent. Each dot is named by its status in words, so the colour is never the only signal (SPEC §3).
 *
 * @param agents the agents of the space.
 */
@Composable
private fun AgentDots(agents: List<AgentDot>) {
    if (agents.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        agents.forEach { agent ->
            val label = stringResource(agentLabelRes(live = agent.live, waiting = agent.waiting, status = agent.status))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(agent.status, agent.live))
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** The waiting badge: a pill with the word, in the blocked colour (SPEC §2.2). */
@Composable
private fun WaitingBadge() {
    val blocked = LocalWhistlTokens.current.blocked
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(blocked.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.spaces_waiting_badge),
            style = MaterialTheme.typography.labelSmall,
            color = blocked,
        )
    }
}

/**
 * The folder's state on a row: nothing when it is resolved, "not checked yet", or the typed reason it is unavailable.
 *
 * @param state the folder's state.
 */
@Composable
private fun FolderLine(state: FolderState) {
    val text = when (state) {
        FolderState.Resolved -> return
        FolderState.NotChecked -> stringResource(R.string.space_folder_not_checked)
        is FolderState.Unavailable -> stringResource(unresolvedTextRes(state.reason))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is FolderState.Unavailable) MaterialTheme.colorScheme.error else LocalWhistlTokens.current.muted,
    )
}

/**
 * The message for an unavailable folder, by its reason.
 *
 * @param reason the typed reason.
 * @return the string resource.
 */
fun unresolvedTextRes(reason: io.github.werlang.whistl.data.UnresolvableReason): Int = when (reason) {
    io.github.werlang.whistl.data.UnresolvableReason.NotFound -> R.string.space_unresolved_not_found
    io.github.werlang.whistl.data.UnresolvableReason.UnsupportedTilde -> R.string.space_unresolved_unsupported_tilde
    io.github.werlang.whistl.data.UnresolvableReason.Timeout -> R.string.space_unresolved_timeout
    io.github.werlang.whistl.data.UnresolvableReason.BadOutput -> R.string.space_unresolved_bad_output
    io.github.werlang.whistl.data.UnresolvableReason.NoHome -> R.string.space_unresolved_no_home
}
