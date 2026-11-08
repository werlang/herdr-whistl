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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.werlang.whistl.AppContainer
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.settings.ADD_KEY_SHEET_TAG
import io.github.werlang.whistl.ui.theme.WhistlTheme
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream

/** The device the screenshots are taken on: 411 × 891 dp at xxhdpi (3 px per dp). */
const val SHOT_DENSITY: Float = 3f

/** The screens the screenshot suite and the guards render (phase 4 r2, SPEC §8). */
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
 * The route each screen starts from, on the demo data: the demo's machine `mac` holds the agents named here.
 *
 * @param screen the screen.
 * @return the route.
 */
fun ShotScreen.route(): String = when (this) {
    ShotScreen.AgentsAll, ShotScreen.AgentsNeedsYou -> Routes.AGENTS
    ShotScreen.AgentWorking -> Routes.agent("mac", "t-working")
    ShotScreen.AgentBlocked -> Routes.agent("mac", "t-blocked")
    ShotScreen.Settings, ShotScreen.AddKeySheet -> Routes.SETTINGS
}

/**
 * Base for the screenshot and guard tests: one activity with the app, rendered on the demo data. Each test sets its locale
 * with a Robolectric qualifier.
 */
@OptIn(ExperimentalTestApi::class)
abstract class ScreenHarness {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The app's container over the debug demo data, for one test. */
    protected fun demoContainer(): AppContainer = AppContainer(RuntimeEnvironment.getApplication(), demoMode = true)

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
        val container = demoContainer()
        val first = variants.first()
        val dark = mutableStateOf(first.dark)
        val scale = mutableStateOf(first.fontScale)
        val route = mutableStateOf(screen.route())
        composeRule.setContent {
            ShotContent(container = container, route = route.value, dark = dark.value, fontScale = scale.value)
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

    /** Brings the screen to the state it is shown in: a filter tapped, or a sheet opened. */
    private fun prepare(screen: ShotScreen) {
        when (screen) {
            ShotScreen.AgentsNeedsYou -> clickByText(RuntimeEnvironment.getApplication().getString(R.string.agents_filter_needs_you))
            ShotScreen.AddKeySheet -> clickByText(
                RuntimeEnvironment.getApplication().getString(R.string.settings_add_key),
                scroll = true,
            )
            else -> Unit
        }
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

    /** Records the frame: the add-key sheet is captured from its own node (it is a separate window), the rest from the root. */
    private fun record(screen: ShotScreen, name: String) {
        if (screen == ShotScreen.AddKeySheet) {
            // The sheet is a separate window, attached a frame after the tap: wait for it before capturing.
            composeRule.waitUntilAtLeastOneExists(hasTestTag(ADD_KEY_SHEET_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
            composeRule.onNodeWithTag(ADD_KEY_SHEET_TAG, useUnmergedTree = true).captureRoboImage(name)
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
        /** How long a sheet may take to appear after it is opened. */
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
