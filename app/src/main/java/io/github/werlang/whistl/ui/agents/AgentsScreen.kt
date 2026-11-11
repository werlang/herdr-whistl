package io.github.werlang.whistl.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.agentLabelRes
import io.github.werlang.whistl.ui.components.CountChip
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.StatusLabel
import io.github.werlang.whistl.ui.statusColor
import io.github.werlang.whistl.ui.theme.AppMark
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/**
 * The Agents list (SPEC §2.4): filter chips with counts, then one row per agent. A row opens the agent's screen.
 *
 * @param state what the list shows.
 * @param onSelect called with the chip tapped.
 * @param onOpen called with the row tapped.
 * @param onMenu called with the row whose action sheet was asked for: the overflow button or a long press.
 * @param modifier the modifier for the screen.
 */
@Composable
fun AgentsScreen(
    state: AgentsUiState,
    onSelect: (AgentsFilter) -> Unit,
    onOpen: (AgentRow) -> Unit,
    onMenu: (AgentRow) -> Unit = {},
    onDismissNotice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val labels = mapOf(
        AgentsFilter.All to stringResource(R.string.agents_filter_all),
        AgentsFilter.NeedsYou to stringResource(R.string.agents_filter_needs_you),
        AgentsFilter.Working to stringResource(R.string.agents_filter_working),
        AgentsFilter.Idle to stringResource(R.string.agents_filter_idle),
    )
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
                    text = stringResource(R.string.agents_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        state.notice?.let { notice ->
            NoticeBar(notice = notice, onDismiss = onDismissNotice)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.chips.forEach { chip ->
                CountChip(
                    label = labels.getValue(chip.filter),
                    count = chip.count,
                    selected = chip.selected,
                    onClick = { onSelect(chip.filter) },
                )
            }
        }
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.agents_filter_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalWhistlTokens.current.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.machineId + "/" + it.address }) { row -> // i18n-ok: lazy-list key, never shown
                    AgentCard(row = row, onClick = { onOpen(row) }, onMenu = { onMenu(row) })
                }
            }
        }
    }
}

/**
 * One agent: its name and address on the first line, its status and "machine · space" on the second. The whole card is the
 * touch target (at least 48 dp; here 72 dp).
 *
 * @param row the agent.
 * @param onClick called when the card is tapped.
 */
@Composable
private fun AgentCard(row: AgentRow, onClick: () -> Unit, onMenu: () -> Unit) {
    val tokens = LocalWhistlTokens.current
    val place = listOf(
        row.machineName,
        row.spaceName ?: stringResource(R.string.common_no_space),
    ).joinToString(stringResource(R.string.separator_place))
    val status = agentLabelRes(live = row.live, waiting = row.waiting, status = row.status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    // A pin has to be visible, not only felt in the order (0.5.1).
                    if (row.pinned) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin),
                            contentDescription = stringResource(R.string.agents_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    }
                    Text(
                        text = row.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = WhistlMono,
                        color = tokens.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.agents_row_menu),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                StatusLabel(label = stringResource(status), color = statusColor(row.status, row.live))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = place,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * What the last action did, said plainly and dismissible (phase 9, SPEC §2.4). It answers "did that work?" without a
 * dialog, and it is the only place the list reports a refusal.
 *
 * @param notice what happened.
 * @param onDismiss called when the bar is dismissed.
 */
@Composable
internal fun NoticeBar(notice: AgentNotice, onDismiss: () -> Unit) {
    val tokens = LocalWhistlTokens.current
    val text = stringResource(
        when (notice) {
            AgentNotice.Interrupted -> R.string.agents_notice_interrupted
            AgentNotice.Closed -> R.string.agents_notice_closed
            AgentNotice.Pinned -> R.string.agents_notice_pinned
            AgentNotice.Unpinned -> R.string.agents_notice_unpinned
            AgentNotice.Renamed -> R.string.agents_notice_renamed
            AgentNotice.Offline -> R.string.agents_notice_offline
            AgentNotice.Gone -> R.string.agents_notice_gone
            AgentNotice.Failed -> R.string.agents_notice_failed
        },
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = tokens.field,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.agents_notice_dismiss),
                    tint = tokens.dim,
                )
            }
        }
    }
}
