package io.github.werlang.whistl.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.werlang.whistl.BuildConfig
import io.github.werlang.whistl.R
import io.github.werlang.whistl.platform.AndroidDemoControl
import io.github.werlang.whistl.platform.AndroidClipboardWriter
import io.github.werlang.whistl.platform.AppCompatLanguageApplier
import io.github.werlang.whistl.ui.agent.AgentScreen
import io.github.werlang.whistl.ui.agent.AgentViewModel
import io.github.werlang.whistl.ui.agents.AgentRow
import io.github.werlang.whistl.ui.agents.AgentsScreen
import io.github.werlang.whistl.ui.agents.AgentsViewModel
import io.github.werlang.whistl.ui.machines.MachinesRoute
import io.github.werlang.whistl.ui.space.SpaceDetailRoute
import io.github.werlang.whistl.ui.space.SpaceDetailViewModel
import io.github.werlang.whistl.ui.spaces.SpacesRoute
import io.github.werlang.whistl.ui.spaces.SpacesViewModel
import io.github.werlang.whistl.ui.machines.MachinesViewModel
import io.github.werlang.whistl.ui.settings.SettingsScreen
import io.github.werlang.whistl.ui.settings.SettingsViewModel

/** The navigation routes of the app. */
object Routes {
    /** Machines (a placeholder this round). */
    const val MACHINES = "machines"

    /** Spaces (a placeholder this round). */
    const val SPACES = "spaces"

    /** The Agents list. */
    const val AGENTS = "agents"

    /** Settings. */
    const val SETTINGS = "settings"

    /** The Agent screen's route pattern: the machine and the terminal address. */
    const val AGENT_PATTERN = "agent/{machineId}/{address}"

    /** The Space detail's route pattern: the machine and the space (phase 4b r2). */
    const val SPACE_PATTERN = "space/{machineId}/{spaceId}"

    /**
     * The route to one agent's screen.
     *
     * @param machineId the machine the agent runs on.
     * @param address the terminal address.
     * @return the route.
     */
    fun agent(machineId: String, address: String): String = "agent/$machineId/$address"

    /**
     * The route to one space's detail.
     *
     * @param machineId the machine the space lives on.
     * @param spaceId the space.
     * @return the route.
     */
    fun space(machineId: String, spaceId: String): String = "space/$machineId/$spaceId"
}

/**
 * The four bottom-navigation destinations (SPEC §2), in bar order.
 *
 * @property labelRes the label string.
 * @property icon the Material icon in the bar.
 * @property route the route the destination opens.
 */
enum class Destination(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val route: String,
) {
    /** Connected machines. */
    Machines(R.string.destination_machines, Icons.Default.Home, Routes.MACHINES),

    /** Spaces grouped by machine. */
    Spaces(R.string.destination_spaces, Icons.AutoMirrored.Filled.List, Routes.SPACES),

    /** Every agent on connected machines; badged with the count waiting for the user. */
    Agents(R.string.destination_agents, Icons.Default.PlayArrow, Routes.AGENTS),

    /** General, shortcut keys and About. */
    Settings(R.string.destination_settings, Icons.Default.Settings, Routes.SETTINGS),
}

/**
 * The app's root: the navigation host over the four destinations and the Agent screen. The bottom bar shows on the four list
 * screens only; the Agents badge is the blocked count (SPEC §2).
 *
 * @param startRoute the route shown first. The screenshot tests start on other screens; the app starts on Machines.
 */
@Composable
fun WhistlApp(startRoute: String = Routes.MACHINES) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Destination.entries.any { it.route == route }
    val container = LocalAppContainer.current
    val machinesModel: MachinesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MachinesViewModel(container.machines, container.agents, container.installed) }
        },
    )
    val spacesModel: SpacesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SpacesViewModel(container.spaces, container.agents, container.machines) }
        },
    )

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { navigateTo(navController, item.route) },
                            icon = { NavIcon(item = item) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == Routes.MACHINES) {
                FloatingActionButton(onClick = machinesModel::openAdd) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.machines_add_content_description),
                    )
                }
            } else if (route == Routes.SPACES) {
                FloatingActionButton(onClick = spacesModel::openNewSpace) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.spaces_new_content_description),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MACHINES,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.MACHINES) {
                val context = LocalContext.current
                val clipboard = remember(context) { AndroidClipboardWriter(context) }
                MachinesRoute(
                    model = machinesModel,
                    machines = container.machines,
                    clientKey = container.clientKey,
                    clipboard = clipboard,
                    modifier = Modifier.fillMaxSize(),
                )
                // Opens the requested start route once the graph exists; the app starts on Machines, so this is a no-op there.
                LaunchedEffect(startRoute) {
                    if (startRoute != Routes.MACHINES) navController.navigate(startRoute)
                }
            }
            composable(Routes.SPACES) {
                SpacesRoute(
                    model = spacesModel,
                    onOpenSpace = { machineId, spaceId -> navController.navigate(Routes.space(machineId, spaceId)) },
                    onAddMachine = { navigateTo(navController, Routes.MACHINES) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Routes.AGENTS) {
                AgentsRoute(onOpen = { row -> navController.navigate(Routes.agent(row.machineId, row.address)) })
            }
            composable(
                route = Routes.SPACE_PATTERN,
                arguments = listOf(
                    navArgument("machineId") { type = NavType.StringType },
                    navArgument("spaceId") { type = NavType.StringType },
                ),
            ) { backStack ->
                SpaceDetailRoute(
                    model = spaceDetailModel(
                        machineId = backStack.arguments?.getString("machineId").orEmpty(),
                        spaceId = backStack.arguments?.getString("spaceId").orEmpty(),
                    ),
                    onOpenAgent = { address ->
                        navController.navigate(Routes.agent(backStack.arguments?.getString("machineId").orEmpty(), address))
                    },
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Routes.SETTINGS) { SettingsRoute() }
            composable(
                route = Routes.AGENT_PATTERN,
                arguments = listOf(
                    navArgument("machineId") { type = NavType.StringType },
                    navArgument("address") { type = NavType.StringType },
                ),
            ) { backStack ->
                AgentRoute(
                    machineId = backStack.arguments?.getString("machineId").orEmpty(),
                    address = backStack.arguments?.getString("address").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Opens a bottom-bar destination: one copy of it on top, no stacking of the same list. */
private fun navigateTo(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The bar icon of a destination. The Agents icon carries the count of agents waiting for the user; it is hidden at zero, and
 * its description says the count.
 *
 * @param item the destination.
 */
@Composable
private fun NavIcon(item: Destination) {
    val container = LocalAppContainer.current
    val blocked by container.agents.blockedCount.collectAsState()
    if (item == Destination.Agents && blocked > 0) {
        val description = pluralStringResource(R.plurals.agents_badge_needs_you, blocked, blocked)
        BadgedBox(badge = { Badge { Text(blocked.toString()) } }) {
            Icon(imageVector = item.icon, contentDescription = description)
        }
    } else {
        Icon(imageVector = item.icon, contentDescription = null)
    }
}

/** The Agents list, with its view model built from the app's container. */
@Composable
private fun AgentsRoute(onOpen: (AgentRow) -> Unit) {
    val container = LocalAppContainer.current
    val model: AgentsViewModel = viewModel(
        factory = viewModelFactory { initializer { AgentsViewModel(container.agents, container.machines) } },
    )
    val state by model.state.collectAsState()
    AgentsScreen(state = state, onSelect = model::select, onOpen = onOpen, modifier = Modifier.fillMaxSize())
}

/** One agent's screen. Its view model is keyed by the agent, so each agent opens its own terminal. */
@Composable
private fun AgentRoute(machineId: String, address: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val model: AgentViewModel = viewModel(
        key = Routes.agent(machineId, address),
        factory = viewModelFactory {
            initializer {
                AgentViewModel(container.terminals, container.agents, container.machines, container.settings, machineId, address)
            }
        },
    )
    AgentScreen(viewModel = model, onBack = onBack, modifier = Modifier.fillMaxSize())
}

/**
 * The Space detail's view model for one space, built from the app's container and keyed by the space, so each space keeps its own.
 *
 * @param machineId the machine the space lives on.
 * @param spaceId the space.
 * @return the view model.
 */
@Composable
private fun spaceDetailModel(machineId: String, spaceId: String): SpaceDetailViewModel {
    val container = LocalAppContainer.current
    return viewModel(
        key = Routes.space(machineId, spaceId),
        factory = viewModelFactory {
            initializer {
                SpaceDetailViewModel(
                    machineId = machineId,
                    spaceId = spaceId,
                    spaces = container.spaces,
                    agents = container.agents,
                    machines = container.machines,
                    installed = container.installed,
                )
            }
        },
    )
}

/** Settings, with its view model built from the app's container and this build's switches. */
@Composable
private fun SettingsRoute() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val model: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.settings,
                    language = AppCompatLanguageApplier,
                    demo = AndroidDemoControl(context),
                    versionName = BuildConfig.VERSION_NAME,
                )
            }
        },
    )
    SettingsScreen(viewModel = model, modifier = Modifier.fillMaxSize())
}

