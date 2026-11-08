package io.github.werlang.whistl.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.werlang.whistl.AppContainer
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.demo.FakeCore
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.ui.machines.ADD_AUTH_PASSWORD_TAG
import io.github.werlang.whistl.ui.machines.ADD_CONNECT_TAG
import io.github.werlang.whistl.ui.machines.ADD_HOST_TAG
import io.github.werlang.whistl.ui.machines.ADD_MACHINE_SHEET_TAG
import io.github.werlang.whistl.ui.machines.ADD_NAME_TAG
import io.github.werlang.whistl.ui.machines.ADD_PASSWORD_TAG
import io.github.werlang.whistl.ui.machines.ADD_REMOVE_TAG
import io.github.werlang.whistl.ui.machines.ADD_TRUST_TAG
import io.github.werlang.whistl.ui.machines.ADD_USER_TAG
import io.github.werlang.whistl.ui.machines.MACHINE_MENU_SHEET_TAG
import io.github.werlang.whistl.ui.machines.MENU_EDIT_TAG
import io.github.werlang.whistl.ui.machines.MENU_REMOVE_TAG
import io.github.werlang.whistl.ui.machines.REMOVE_MACHINE_SHEET_TAG
import io.github.werlang.whistl.ui.settings.ADD_KEY_SHEET_TAG
import io.github.werlang.whistl.ui.agent.AGENT_MENU_SHEET_TAG
import io.github.werlang.whistl.ui.machines.HOST_KEY_SHEET_TAG
import io.github.werlang.whistl.ui.settings.LICENSES_SHEET_TAG
import io.github.werlang.whistl.ui.space.NEW_AGENT_TAG
import io.github.werlang.whistl.ui.space.PICKER_SHEET_TAG
import io.github.werlang.whistl.ui.spaces.CLOSE_SPACE_SHEET_TAG
import io.github.werlang.whistl.ui.spaces.MENU_SPACE_CLOSE_TAG
import io.github.werlang.whistl.ui.spaces.MENU_SPACE_RENAME_TAG
import io.github.werlang.whistl.ui.spaces.NEW_SPACE_FOLDER_TAG
import io.github.werlang.whistl.ui.spaces.NEW_SPACE_SHEET_TAG
import io.github.werlang.whistl.ui.spaces.RENAME_SPACE_SHEET_TAG
import io.github.werlang.whistl.ui.spaces.SPACE_MENU_SHEET_TAG
import io.github.werlang.whistl.ui.theme.WhistlTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream

/** The device the screenshots are taken on: 411 × 891 dp at xxhdpi (3 px per dp). */
const val SHOT_DENSITY: Float = 3f

/** The screens the screenshot suite and the guards render (phase 4 r2 and phase 4b r1, SPEC §8). */
enum class ShotScreen(val slug: String) {
    /** The shell on the Agents list, filter All. */
    AgentsAll("agents-all"),

    /** The Agents list filtered to the agents that need the user. */
    AgentsNeedsYou("agents-needs-you"),

    /** The Agent terminal of a working agent. */
    AgentWorking("agent-working"),

    /** The Agent terminal of a blocked agent. */
    AgentBlocked("agent-blocked"),

    /** The Settings screen. */
    Settings("settings"),

    /** The Add key sheet, open over Settings. */
    AddKeySheet("add-key-sheet"),

    /** The Machines list with the demo's four machines, one in each state. */
    Machines("machines"),

    /** The Machines list with no machines: the empty state. */
    MachinesEmpty("machines-empty"),

    /** The Machines list with one machine whose name and address are long. */
    MachinesLong("machines-long"),

    /** The Add machine sheet in key mode, with a host and a user typed, showing this device's public key. */
    AddMachineKey("add-machine-key"),

    /** The Add machine sheet in password mode. */
    AddMachinePassword("add-machine-password"),

    /** The Add machine sheet asking to trust a host key nobody has confirmed. */
    HostKeyUnknown("host-key-unknown"),

    /** The Add machine sheet refusing a host key that changed. */
    HostKeyChanged("host-key-changed"),

    /** A machine's card menu. */
    MachineMenu("machine-menu"),

    /** The Edit machine sheet of the demo's first machine, prefilled from it (phase 7 P2). */
    EditMachine("edit-machine"),

    /** The confirmation before a machine is removed. */
    RemoveMachineConfirm("remove-machine-confirm"),

    /** The Spaces list with the demo's spaces: pinned, a waiting badge, an offline group, an unresolved folder. */
    Spaces("spaces"),

    /** The Spaces list with no space on any machine: the empty state. */
    SpacesEmpty("spaces-empty"),

    /** The Spaces list with a long space name and a long folder, at 1.3× font scale. */
    SpacesLong("spaces-long"),

    /** The New space sheet, with a folder typed. */
    NewSpace("new-space"),

    /** The New space sheet with no machine added. */
    NewSpaceNoMachine("new-space-no-machine"),

    /** A space's row menu. */
    SpaceMenu("space-menu"),

    /** The rename sheet for a space. */
    RenameSpace("rename-space"),

    /** The close confirmation for a space with live agents. */
    CloseSpaceConfirm("close-space-confirm"),

    /** The Space detail of a connected space with a blocked agent. */
    SpaceDetail("space-detail"),

    /** The Space detail of a space on an offline machine: New agent is off. */
    SpaceDetailOffline("space-detail-offline"),

    /** The New agent picker, open over the Space detail. */
    NewAgentPicker("new-agent-picker"),

    /** The Agent screen's options sheet (Interrupt, Stop and remove), open over a working agent. */
    AgentMenu("agent-menu"),

    /** The open-source licences sheet, open over Settings. */
    LicencesSheet("licences-sheet"),

    /** A machine's host key nobody has confirmed, shown as the card's sheet on the Machines list. */
    MachineHostKeySheet("host-key-card-unknown"),
}

/**
 * One rendering of a screen: the theme and the font scale.
 *
 * @property dark whether the dark theme is on.
 * @property fontScale the font scale (1.3 for the large-text variant).
 */
data class ShotVariant(val dark: Boolean, val fontScale: Float = 1f) {
    /** The theme part of the file name: `dark` or `light`. */
    val theme: String get() = if (dark) "dark" else "light"

    /** Whether this is the 1.3× font-scale variant. */
    val largeFont: Boolean get() = fontScale != 1f
}

/**
 * The app as a screenshot shows it: the container, the theme, and the device density with the variant's font scale.
 *
 * @param container the app's object graph, over the debug demo data.
 * @param route the route to show first.
 * @param dark whether the dark theme is on.
 * @param fontScale the font scale.
 */
@Composable
fun ShotContent(container: AppContainer, route: String, dark: Boolean, fontScale: Float) {
    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalDensity provides Density(density = SHOT_DENSITY, fontScale = fontScale),
    ) {
        WhistlTheme(darkTheme = dark) {
            WhistlApp(startRoute = route)
        }
    }
}

/**
 * The route each screen starts from, on the demo data: the demo's `mac` holds the agents named here.
 *
 * @return the route.
 */
fun ShotScreen.route(): String = when (this) {
    ShotScreen.AgentsAll, ShotScreen.AgentsNeedsYou -> Routes.AGENTS
    ShotScreen.AgentWorking -> Routes.agent("mac", "t-working")
    ShotScreen.AgentBlocked -> Routes.agent("mac", "t-blocked")
    ShotScreen.Settings, ShotScreen.AddKeySheet -> Routes.SETTINGS
    ShotScreen.Machines, ShotScreen.MachinesEmpty, ShotScreen.MachinesLong, ShotScreen.AddMachineKey,
    ShotScreen.AddMachinePassword, ShotScreen.HostKeyUnknown, ShotScreen.HostKeyChanged, ShotScreen.MachineMenu,
    ShotScreen.RemoveMachineConfirm, ShotScreen.EditMachine,
    -> Routes.MACHINES
    ShotScreen.SpaceDetail, ShotScreen.NewAgentPicker -> Routes.space("mac", "sp_web")
    ShotScreen.SpaceDetailOffline -> Routes.space("lab", "sp_lab")
    ShotScreen.AgentMenu -> Routes.agent("mac", "t-working")
    ShotScreen.LicencesSheet -> Routes.SETTINGS
    ShotScreen.MachineHostKeySheet -> Routes.MACHINES
    ShotScreen.Spaces, ShotScreen.SpacesEmpty, ShotScreen.SpacesLong, ShotScreen.NewSpace, ShotScreen.NewSpaceNoMachine,
    ShotScreen.SpaceMenu, ShotScreen.RenameSpace, ShotScreen.CloseSpaceConfirm,
    -> Routes.SPACES
}

/**
 * The test tag of the sheet a screen shows over its list, or `null` when the screen is not a sheet. The sheet is captured from
 * its own node, because it is a separate window.
 *
 * @return the tag, or `null`.
 */
fun ShotScreen.sheetTag(): String? = when (this) {
    ShotScreen.AddKeySheet -> ADD_KEY_SHEET_TAG
    ShotScreen.AddMachineKey, ShotScreen.AddMachinePassword, ShotScreen.HostKeyUnknown, ShotScreen.HostKeyChanged,
    ShotScreen.EditMachine,
    -> ADD_MACHINE_SHEET_TAG

    ShotScreen.MachineMenu -> MACHINE_MENU_SHEET_TAG
    ShotScreen.RemoveMachineConfirm -> REMOVE_MACHINE_SHEET_TAG
    ShotScreen.NewSpace, ShotScreen.NewSpaceNoMachine -> NEW_SPACE_SHEET_TAG
    ShotScreen.SpaceMenu -> SPACE_MENU_SHEET_TAG
    ShotScreen.RenameSpace -> RENAME_SPACE_SHEET_TAG
    ShotScreen.CloseSpaceConfirm -> CLOSE_SPACE_SHEET_TAG
    ShotScreen.NewAgentPicker -> PICKER_SHEET_TAG
    ShotScreen.AgentMenu -> AGENT_MENU_SHEET_TAG
    ShotScreen.LicencesSheet -> LICENSES_SHEET_TAG
    ShotScreen.MachineHostKeySheet -> HOST_KEY_SHEET_TAG
    else -> null
}

/**
 * The text a screen must show, read from the resources of the locale under test, or from the demo data for a screen named after
 * one of its items.
 *
 * @return the text.
 */
fun ShotScreen.expectedText(): String {
    val app = RuntimeEnvironment.getApplication()
    return when (this) {
        ShotScreen.AgentsAll, ShotScreen.AgentsNeedsYou -> app.getString(R.string.agents_title)
        ShotScreen.AgentWorking -> "claude"
        ShotScreen.AgentBlocked -> "codex"
        ShotScreen.Settings -> app.getString(R.string.settings_title)
        ShotScreen.AddKeySheet -> app.getString(R.string.settings_add_key)
        ShotScreen.Machines, ShotScreen.MachinesEmpty, ShotScreen.MachinesLong -> app.getString(R.string.machines_title)
        ShotScreen.EditMachine -> app.getString(R.string.edit_machine_title)
        ShotScreen.AddMachineKey, ShotScreen.AddMachinePassword, ShotScreen.HostKeyUnknown, ShotScreen.HostKeyChanged ->
            app.getString(R.string.add_machine_title)

        ShotScreen.MachineMenu -> "mac"
        ShotScreen.RemoveMachineConfirm -> app.getString(R.string.machine_confirm_remove_title, "mac")
        ShotScreen.Spaces, ShotScreen.SpacesEmpty, ShotScreen.SpacesLong -> app.getString(R.string.destination_spaces)
        ShotScreen.NewSpace, ShotScreen.NewSpaceNoMachine -> app.getString(R.string.space_new_title)
        ShotScreen.SpaceMenu -> "api"
        ShotScreen.RenameSpace -> app.getString(R.string.space_rename_title)
        ShotScreen.CloseSpaceConfirm -> app.getString(R.string.space_close_title, "api")
        ShotScreen.SpaceDetail -> "web"
        ShotScreen.SpaceDetailOffline -> "lab"
        ShotScreen.NewAgentPicker -> app.getString(R.string.new_agent)
        ShotScreen.AgentMenu -> app.getString(R.string.agent_interrupt)
        ShotScreen.LicencesSheet -> app.getString(R.string.licenses_title)
        ShotScreen.MachineHostKeySheet -> app.getString(R.string.add_host_key_title, "build")
    }
}

/**
 * Puts the demo into the state a screen needs, before its first frame: the machines it lists.
 *
 * @param core the demo's data.
 * @param container the container the app is built from.
 */
private fun ShotScreen.seed(core: FakeCore, container: AppContainer) {
    when (this) {
        ShotScreen.MachinesEmpty -> clearMachines(container)
        ShotScreen.MachinesLong -> {
            clearMachines(container)
            core.addMachine(LONG_MACHINE, ConnectionState.Connected(SHOT_HELLO))
        }

        ShotScreen.SpacesEmpty -> runBlocking {
            // Only mac stays, with none of its spaces: the list has machines and no space.
            container.machines.machines().value.map { it.id }.filter { it != "mac" }.forEach { container.machines.remove(it) }
            container.spaces.groups().value.flatMap { group -> group.spaces.map { group.machineId to it.id } }
                .forEach { (machineId, spaceId) -> container.spaces.close(machineId, spaceId) }
        }

        ShotScreen.SpacesLong -> runBlocking {
            container.spaces.create("mac", folder = LONG_FOLDER, name = LONG_SPACE_NAME)
        }

        ShotScreen.NewSpaceNoMachine -> clearMachines(container)
        ShotScreen.MachineHostKeySheet -> core.setConnection(
            "build",
            ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:q8V3mZp1kT0rN7wLx2cY4bH6sJ9dF5gA3eU8iO0pQ1w"),
        )
        else -> Unit
    }
}

/** A folder with a long path, for the layout check of a space row. */
private const val LONG_FOLDER: String = "/home/demo/work/nightly/container-images/platform-team/build-pipeline-for-the-web"

/** A long space name, for the layout check of a space row. */
private const val LONG_SPACE_NAME: String = "nightly-container-image-build-pipeline-platform"

/**
 * Removes every machine the demo starts with.
 *
 * @param container the container whose machines are removed.
 */
private fun clearMachines(container: AppContainer) {
    runBlocking {
        container.machines.machines().value.map { it.id }.forEach { container.machines.remove(it) }
    }
}

/** A machine with a long name and address, for the layout check at 1.3× font scale. */
private val LONG_MACHINE = Machine(
    id = "long",
    name = "build-server-for-the-nightly-container-images",
    host = "build-server.internal.example.net",
    port = 2222,
    user = "deploy-user",
)

/** The hello a screenshot's connected machine reports. */
private val SHOT_HELLO = HelloResult(
    coreId = "demo",
    coreName = "demo",
    features = emptyList(),
    generation = 1,
    methods = emptyList(),
    version = "0.1.18",
)

/**
 * Base for the screenshot and guard tests: one activity with the app, rendered on the demo data. Each test sets its locale
 * with a Robolectric qualifier.
 */
@OptIn(ExperimentalTestApi::class)
abstract class ScreenHarness {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The app's container over a fresh demo core, with the core it was built from.
     *
     * @return the core and the container.
     */
    private fun demoApp(): Pair<FakeCore, AppContainer> {
        val core = FakeCore()
        return core to AppContainer(RuntimeEnvironment.getApplication(), demoCore = core)
    }

    /**
     * Renders [screen] in each variant, in order, and returns the frame of each as PNG bytes.
     *
     * Each frame is also recorded through Roborazzi as `<screen>_<theme>_<locale>[_fs13].png` (record and verify modes).
     *
     * @param screen the screen.
     * @param variants the renderings, in order.
     * @param locale the locale token of the file names, for example `en` or `pt-BR`.
     * @return the frame of each variant.
     */
    protected fun renderVariants(screen: ShotScreen, variants: List<ShotVariant>, locale: String): Map<ShotVariant, ByteArray> {
        val first = variants.first()
        val dark = mutableStateOf(first.dark)
        val scale = mutableStateOf(first.fontScale)
        val (core, container) = demoApp()
        screen.seed(core, container)
        composeRule.setContent {
            ShotContent(container = container, route = screen.route(), dark = dark.value, fontScale = scale.value)
        }
        composeRule.waitForIdle()
        prepare(screen)
        assertShowsItsText(screen, locale)
        val frames = LinkedHashMap<ShotVariant, ByteArray>()
        variants.forEach { variant ->
            composeRule.runOnIdle {
                dark.value = variant.dark
                scale.value = variant.fontScale
            }
            composeRule.waitForIdle()
            val name = fileName(screen, variant, locale)
            record(screen, name)
            val png = frameBytes()
            assertRealFrame(screen, variant, png)
            frames[variant] = png
        }
        return frames
    }

    /**
     * Shows [screen] in the dark theme with its sheet or filter open, for the guards that read the semantics tree.
     *
     * @param screen the screen.
     */
    protected fun showForGuard(screen: ShotScreen) {
        val (core, container) = demoApp()
        screen.seed(core, container)
        composeRule.setContent {
            ShotContent(container = container, route = screen.route(), dark = true, fontScale = 1f)
        }
        composeRule.waitForIdle()
        prepare(screen)
    }

    /**
     * Brings the screen to the state it is shown in: a filter tapped, a sheet opened, or a form filled as a user would.
     *
     * @param screen the screen.
     */
    private fun prepare(screen: ShotScreen) {
        val app = RuntimeEnvironment.getApplication()
        when (screen) {
            ShotScreen.AgentsNeedsYou -> clickByText(app.getString(R.string.agents_filter_needs_you))
            ShotScreen.AddKeySheet -> clickByText(app.getString(R.string.settings_add_key), scroll = true)
            ShotScreen.MachineMenu -> openMachineMenu()
            ShotScreen.RemoveMachineConfirm -> {
                openMachineMenu()
                composeRule.onNodeWithTag(MENU_REMOVE_TAG, useUnmergedTree = true).performClick()
            }

            ShotScreen.EditMachine -> {
                openMachineMenu()
                composeRule.onNodeWithTag(MENU_EDIT_TAG, useUnmergedTree = true).performClick()
            }

            ShotScreen.AddMachineKey -> {
                openAddMachine()
                typeHostAndUser(host = "192.168.0.10", user = "pi")
            }

            ShotScreen.AddMachinePassword -> {
                openAddMachine()
                // A host that is not valid, with a password typed: Connect is on, and the form reports the host under its field.
                typeHostAndUser(host = "bad host!", user = "pi")
                composeRule.onNodeWithTag(ADD_AUTH_PASSWORD_TAG, useUnmergedTree = true).performClick()
                composeRule.onNodeWithTag(ADD_PASSWORD_TAG, useUnmergedTree = true).performTextInput("hunter2")
                composeRule.onNodeWithTag(ADD_CONNECT_TAG, useUnmergedTree = true).performClick()
                composeRule.waitForIdle()
            }

            ShotScreen.HostKeyUnknown -> {
                openAddMachine()
                typeHostAndUser(host = "unknown.demo", user = "ci", name = "build")
                connectAndWait(ADD_TRUST_TAG)
            }

            ShotScreen.HostKeyChanged -> {
                openAddMachine()
                typeHostAndUser(host = "changed.demo", user = "ci", name = "build")
                connectAndWait(ADD_REMOVE_TAG)
            }

            ShotScreen.NewSpace -> {
                openNewSpace()
                composeRule.onNodeWithTag(NEW_SPACE_FOLDER_TAG, useUnmergedTree = true).performTextInput("/home/demo/web-app")
            }

            ShotScreen.NewSpaceNoMachine -> openNewSpace()
            ShotScreen.SpaceMenu -> openSpaceMenu()
            ShotScreen.RenameSpace -> {
                openSpaceMenu()
                composeRule.onNodeWithTag(MENU_SPACE_RENAME_TAG, useUnmergedTree = true).performClick()
            }

            ShotScreen.CloseSpaceConfirm -> {
                openSpaceMenu()
                composeRule.onNodeWithTag(MENU_SPACE_CLOSE_TAG, useUnmergedTree = true).performClick()
            }

            ShotScreen.NewAgentPicker -> composeRule.onNodeWithTag(NEW_AGENT_TAG, useUnmergedTree = true).performClick()
            ShotScreen.AgentMenu -> {
                val description = RuntimeEnvironment.getApplication().getString(R.string.agent_options)
                composeRule.onNode(hasContentDescription(description) and hasClickAction()).performClick()
            }

            ShotScreen.LicencesSheet -> {
                // The About section is the third item of the Settings list; it is only composed once the list reaches it.
                composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(SETTINGS_ABOUT_INDEX)
                clickByText(RuntimeEnvironment.getApplication().getString(R.string.settings_licenses))
            }
            else -> Unit
        }
        screen.sheetTag()?.let { tag ->
            composeRule.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = SHEET_TIMEOUT_MS)
        }
        composeRule.waitForIdle()
    }

    /** Opens the New space sheet from the Spaces add button. */
    private fun openNewSpace() {
        val description = RuntimeEnvironment.getApplication().getString(R.string.spaces_new_content_description)
        composeRule.onNode(hasContentDescription(description) and hasClickAction()).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(NEW_SPACE_SHEET_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /** Opens the first space's row menu, as a user taps its options button. */
    private fun openSpaceMenu() {
        val description = RuntimeEnvironment.getApplication().getString(R.string.space_options)
        composeRule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst().performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(SPACE_MENU_SHEET_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /** Opens the first machine's card menu, as a user taps its menu button. */
    private fun openMachineMenu() {
        val description = RuntimeEnvironment.getApplication().getString(R.string.machine_options)
        composeRule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst().performClick()
        composeRule.waitForIdle()
    }

    /** Opens the Add machine sheet from the add button. */
    private fun openAddMachine() {
        val description = RuntimeEnvironment.getApplication().getString(R.string.machines_add_content_description)
        composeRule.onNode(hasContentDescription(description) and hasClickAction()).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(ADD_MACHINE_SHEET_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /**
     * Types the host, the user and, when given, the display name into the Add machine form.
     *
     * @param host the host text.
     * @param user the user text.
     * @param name the display name, or `null` to leave the default.
     */
    private fun typeHostAndUser(host: String, user: String, name: String? = null) {
        composeRule.onNodeWithTag(ADD_HOST_TAG, useUnmergedTree = true).performTextInput(host)
        composeRule.onNodeWithTag(ADD_USER_TAG, useUnmergedTree = true).performTextInput(user)
        if (name != null) composeRule.onNodeWithTag(ADD_NAME_TAG, useUnmergedTree = true).performTextInput(name)
        composeRule.waitForIdle()
    }

    /**
     * Taps Connect and waits for the stage that shows [tag]: the connection attempt runs in the background.
     *
     * @param tag the test tag of the stage's first button.
     */
    private fun connectAndWait(tag: String) {
        composeRule.onNodeWithTag(ADD_CONNECT_TAG, useUnmergedTree = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = SHEET_TIMEOUT_MS)
        composeRule.waitForIdle()
    }

    /**
     * Taps the one clickable node whose text contains [label].
     *
     * @param label the text to find.
     * @param scroll whether the node is inside a scrolling list, which must be scrolled to it first.
     */
    protected fun clickByText(label: String, scroll: Boolean = false) {
        val node = composeRule.onNode(hasText(label, substring = true) and hasClickAction())
        (if (scroll) node.performScrollTo() else node).performClick()
    }

    /**
     * The screen must show its title, read from the resources of the locale under test. A blank screen, or a frame of the
     * wrong screen, has no such text in its tree.
     *
     * @param screen the screen.
     * @param locale the locale token of the test, for the message.
     */
    private fun assertShowsItsText(screen: ShotScreen, locale: String) {
        val text = screen.expectedText()
        val nodes = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("${screen.slug} ($locale) does not show its text \"$text\"", nodes.isNotEmpty())
    }

    /**
     * A frame is a real screen when it has at least [MIN_DISTINCT_COLOURS] distinct colours and a luminance spread of at least
     * [MIN_LUMINANCE_SPREAD]. A blank frame, or one tinted in a single colour, has one colour and no spread; the smallest golden
     * the suite records has far more.
     *
     * @param screen the screen.
     * @param variant the rendering.
     * @param png the frame.
     */
    private fun assertRealFrame(screen: ShotScreen, variant: ShotVariant, png: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val colours = pixels.toHashSet().size
        var sum = 0.0
        var sumSquares = 0.0
        for (pixel in pixels) {
            val luminance = 0.2126 * ((pixel shr 16) and 0xff) + 0.7152 * ((pixel shr 8) and 0xff) + 0.0722 * (pixel and 0xff)
            sum += luminance
            sumSquares += luminance * luminance
        }
        val mean = sum / pixels.size
        val spread = kotlin.math.sqrt((sumSquares / pixels.size - mean * mean).coerceAtLeast(0.0))
        assertTrue(
            "${screen.slug} ${variant.theme}: $colours distinct colours, need $MIN_DISTINCT_COLOURS",
            colours >= MIN_DISTINCT_COLOURS,
        )
        assertTrue(
            "${screen.slug} ${variant.theme}: luminance spread ${"%.1f".format(spread)}, need $MIN_LUMINANCE_SPREAD",
            spread >= MIN_LUMINANCE_SPREAD,
        )
    }

    /**
     * Records the frame: a sheet from its own node (it is a separate window), the rest from the root.
     *
     * @param screen the screen.
     * @param name the golden's file name.
     */
    private fun record(screen: ShotScreen, name: String) {
        val tag = screen.sheetTag()
        if (tag != null) {
            // The sheet is a separate window, attached a frame after it is opened: wait for it before capturing.
            composeRule.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = SHEET_TIMEOUT_MS)
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(name)
        } else {
            composeRule.onRoot().captureRoboImage(name)
        }
    }

    /**
     * The window's frame as PNG bytes, for the test's own assertions. It is drawn from the decor view: a sheet is in its own
     * window and is not part of it, so the assertions on a sheet are about the screen behind it.
     *
     * @return the PNG bytes.
     */
    protected fun frameBytes(): ByteArray {
        val view = composeRule.activity.window.decorView
        var png = ByteArray(0)
        composeRule.runOnUiThread {
            val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frame))
            png = ByteArrayOutputStream().use { out ->
                check(frame.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
                out.toByteArray()
            }
        }
        return png
    }

    companion object {
        /** The index of the About section in the Settings list: General, Shortcut keys, About. */
        const val SETTINGS_ABOUT_INDEX: Int = 2

        /** How long a sheet or a stage may take to appear after it is opened or after a connection attempt. */
        const val SHEET_TIMEOUT_MS: Long = 5_000L

        /**
         * The fewest distinct colours a real frame has. The smallest golden the suite records has 553 (sampled every fourth
         * pixel); a blank or single-tint frame has one. Text anti-aliasing is what makes the difference.
         */
        const val MIN_DISTINCT_COLOURS: Int = 64

        /**
         * The least luminance spread (standard deviation, 0 to 255) a real frame has. The smallest golden has 13.5; a blank frame
         * has 0.
         */
        const val MIN_LUMINANCE_SPREAD: Double = 5.0

        /** PNG is lossless; the quality argument is ignored by the encoder. */
        const val PNG_QUALITY: Int = 100

        /** The size floor of a real frame (phase 4 brief: more than a few KiB). */
        const val MIN_PNG_BYTES: Int = 4 * 1024

        /**
         * The file name of a rendering: `<screen>_<theme>_<locale>[_fs13].png`.
         *
         * @param screen the screen.
         * @param variant the rendering.
         * @param locale the locale token.
         * @return the name.
         */
        fun fileName(screen: ShotScreen, variant: ShotVariant, locale: String): String =
            "${screen.slug}_${variant.theme}_$locale${if (variant.largeFont) "_fs13" else ""}.png"
    }
}
