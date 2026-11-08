package io.github.werlang.whistl.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import io.github.werlang.whistl.ui.machines.MENU_REMOVE_TAG
import io.github.werlang.whistl.ui.machines.REMOVE_MACHINE_SHEET_TAG
import io.github.werlang.whistl.ui.settings.ADD_KEY_SHEET_TAG
import io.github.werlang.whistl.ui.theme.WhistlTheme
import kotlinx.coroutines.runBlocking
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

    /** The confirmation before a machine is removed. */
    RemoveMachineConfirm("remove-machine-confirm"),
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
    ShotScreen.RemoveMachineConfirm,
    -> Routes.MACHINES
}

/**
 * The test tag of the sheet a screen shows over its list, or `null` when the screen is not a sheet. The sheet is captured from
 * its own node, because it is a separate window.
 *
 * @return the tag, or `null`.
 */
fun ShotScreen.sheetTag(): String? = when (this) {
    ShotScreen.AddKeySheet -> ADD_KEY_SHEET_TAG
    ShotScreen.AddMachineKey, ShotScreen.AddMachinePassword, ShotScreen.HostKeyUnknown, ShotScreen.HostKeyChanged ->
        ADD_MACHINE_SHEET_TAG

    ShotScreen.MachineMenu -> MACHINE_MENU_SHEET_TAG
    ShotScreen.RemoveMachineConfirm -> REMOVE_MACHINE_SHEET_TAG
    else -> null
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

        else -> Unit
    }
}

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
        val frames = LinkedHashMap<ShotVariant, ByteArray>()
        variants.forEach { variant ->
            composeRule.runOnIdle {
                dark.value = variant.dark
                scale.value = variant.fontScale
            }
            composeRule.waitForIdle()
            val name = fileName(screen, variant, locale)
            record(screen, name)
            frames[variant] = frameBytes()
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

            else -> Unit
        }
        screen.sheetTag()?.let { tag ->
            composeRule.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = SHEET_TIMEOUT_MS)
        }
        composeRule.waitForIdle()
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
        /** How long a sheet or a stage may take to appear after it is opened or after a connection attempt. */
        const val SHEET_TIMEOUT_MS: Long = 5_000L

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
