package io.github.werlang.whistl.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/**
 * The four bottom-navigation destinations (SPEC §2), in bar order.
 *
 * @property labelRes string resource for the destination's label.
 * @property icon the core Material icon shown in the bar.
 */
enum class Destination(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** Connected machines; the only destination with a shell this round. */
    Machines(R.string.destination_machines, Icons.Default.Home),

    /** Spaces grouped by machine. */
    Spaces(R.string.destination_spaces, Icons.AutoMirrored.Filled.List),

    /** Every agent on connected machines; badged with the blocked count. */
    Agents(R.string.destination_agents, Icons.Default.PlayArrow),

    /** General, shortcut keys and About. */
    Settings(R.string.destination_settings, Icons.Default.Settings),
}

/**
 * Root composable: bottom navigation over the four [Destination]s.
 *
 * Selecting a destination switches the body. Only [Destination.Machines] has a shell
 * this round; the others show placeholder text.
 *
 * @param blockedAgentCount count shown on the Agents badge. Zero hides the badge.
 */
@Composable
fun WhistlApp(blockedAgentCount: Int = 0) {
    var destination by rememberSaveable { mutableStateOf(Destination.Machines) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { destination = item },
                        icon = {
                            NavIcon(
                                item = item,
                                badgeCount = if (item == Destination.Agents) blockedAgentCount else 0,
                            )
                        },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination == Destination.Machines) {
                FloatingActionButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.machines_add_content_description),
                    )
                }
            }
        },
    ) { innerPadding ->
        val body = Modifier
            .padding(innerPadding)
            .fillMaxSize()
        when (destination) {
            Destination.Machines -> MachinesScreen(body)
            Destination.Spaces -> PlaceholderScreen(R.string.spaces_placeholder, body)
            Destination.Agents -> PlaceholderScreen(R.string.agents_placeholder, body)
            Destination.Settings -> PlaceholderScreen(R.string.settings_placeholder, body)
        }
    }
}

/**
 * Icon for a bar item. A badge is drawn only when [badgeCount] is positive.
 *
 * The visible label already names the destination, so the icon has no content description.
 */
@Composable
private fun NavIcon(item: Destination, badgeCount: Int) {
    val icon: @Composable () -> Unit = {
        Icon(imageVector = item.icon, contentDescription = null)
    }
    if (badgeCount > 0) {
        BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) { icon() }
    } else {
        icon()
    }
}

/**
 * Machines destination shell: title, empty-state text, 16dp side gutter.
 */
@Composable
private fun MachinesScreen(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.machines_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.machines_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalWhistlTokens.current.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Placeholder body for a destination that has no screen yet.
 *
 * @param text string resource shown centred.
 */
@Composable
private fun PlaceholderScreen(@StringRes text: Int, modifier: Modifier) {
    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalWhistlTokens.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}
