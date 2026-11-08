package io.github.werlang.whistl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import io.github.werlang.whistl.AppContainer
import io.github.werlang.whistl.demo.FakeCore
import io.github.werlang.whistl.ui.agent.AGENT_SCREEN_TAG
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Route arguments survive encoding (phase 6 P0-8). The app starts on the real Agent route built by [Routes.agent] for an address
 * that holds `/`, `%` and `#`, and the Agent header must show that exact address. The address also holds `%41`: a second decode
 * would turn it into `A`, so this test fails on a double decode as well as on a raw `/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class NavigationEncodingTest : ScreenHarness() {

    /** The address under test: a path separator, a percent sign, a hash, and a `%41` that decodes to `A` only if decoded twice. */
    private val address = "pane/one%41#two"

    /** The Agent route for [address] on the demo machine `mac` shows [address] in its header, unchanged. */
    @Test
    fun anAddressWithSlashPercentAndHashReachesTheAgentScreenExactly() {
        val container = AppContainer(RuntimeEnvironment.getApplication(), demoCore = FakeCore())
        composeRule.setContent {
            ShotContent(container = container, route = Routes.agent("mac", address), dark = true, fontScale = 1f)
        }

        composeRule.waitUntilAtLeastOneExists(hasTestTag(AGENT_SCREEN_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
        assertTrue(
            "the Agent header shows the exact address",
            composeRule.onAllNodesWithText(address, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
