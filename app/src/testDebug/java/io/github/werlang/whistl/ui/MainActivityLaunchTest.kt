package io.github.werlang.whistl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.werlang.whistl.MainActivity
import io.github.werlang.whistl.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app launched the way the user launches it: [MainActivity] itself, not a stand-in host, which the screenshot tests use.
 * It must come up on the Machines screen. A navigation host whose event dispatcher is missing fails the composition here,
 * so the regression is caught before a device run. The activity is an AppCompat one, so its view-tree owners are the case
 * that matters.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class MainActivityLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** The launch lands on the Machines screen: its title is on screen (the bottom bar has a label of the same text). */
    @Test
    fun launchesOnTheMachinesScreen() {
        composeRule.waitForIdle()
        val title = RuntimeEnvironment.getApplication().getString(R.string.machines_title)
        val shown = composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        assertTrue("MainActivity did not render the Machines screen: no node reads \"$title\"", shown)
    }
}
