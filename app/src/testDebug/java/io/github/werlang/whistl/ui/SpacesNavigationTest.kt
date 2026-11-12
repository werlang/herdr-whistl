package io.github.werlang.whistl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.werlang.whistl.AppContainer
import io.github.werlang.whistl.R
import io.github.werlang.whistl.demo.FakeCore
import io.github.werlang.whistl.ui.agent.AGENT_SCREEN_TAG
import io.github.werlang.whistl.ui.space.NEW_AGENT_TAG
import io.github.werlang.whistl.ui.space.PICKER_SHEET_TAG
import io.github.werlang.whistl.ui.space.PICKER_TERMINAL_TAG
import io.github.werlang.whistl.ui.agents.AGENTS_SCREEN_TAG
import io.github.werlang.whistl.ui.space.SPACE_DETAIL_TAG
import io.github.werlang.whistl.ui.spaces.MENU_SPACE_NEW_AGENT_TAG
import io.github.werlang.whistl.ui.spaces.SPACES_LIST_TAG
import io.github.werlang.whistl.ui.spaces.spaceRowTag
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The navigation of the Spaces destination (phase 4b r2, §9), on the real `NavHost` over the demo data: a space row opens its
 * detail; the picker's Terminal choice starts a shell and opens its Agent screen; a disabled New agent opens no picker.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class SpacesNavigationTest : ScreenHarness() {

    /** Opens one space's own screen through its row menu: that is where an agent is started in it (0.6.4). */
    private fun openSpaceDetail(machineId: String, spaceId: String) {
        val options = RuntimeEnvironment.getApplication().getString(R.string.space_options)
        composeRule.onNode(hasContentDescription(options) and hasAnyAncestor(hasTestTag(spaceRowTag(machineId, spaceId))), useUnmergedTree = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(MENU_SPACE_NEW_AGENT_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
        composeRule.onNodeWithTag(MENU_SPACE_NEW_AGENT_TAG, useUnmergedTree = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(SPACE_DETAIL_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /** Starts the app on Machines, with a fresh demo core, and opens the Spaces destination from its bar. */
    private fun openSpaces(): AppContainer {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app, demoCore = FakeCore())
        composeRule.setContent {
            ShotContent(container = container, route = io.github.werlang.whistl.ui.Routes.MACHINES, dark = true, fontScale = 1f)
        }
        composeRule.waitForIdle()
        clickByText(app.getString(R.string.destination_spaces))
        composeRule.waitUntilAtLeastOneExists(hasTestTag(SPACES_LIST_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
        return container
    }

    /** A space row opens the Agents list, filtered to that space, not a screen of its own (0.6.4). */
    @Test
    fun aSpaceRowOpensTheAgentsList() {
        openSpaces()

        composeRule.onNodeWithTag(spaceRowTag("mac", "sp_web"), useUnmergedTree = true).performClick()

        composeRule.waitUntilAtLeastOneExists(hasTestTag(AGENTS_SCREEN_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /** Starting an agent in a space is one step further: the space's menu, then its own screen, then the picker. */
    @Test
    fun theSpaceMenuStartsANewAgentInTheSpace() {
        openSpaces()

        openSpaceDetail("mac", "sp_web")
        composeRule.onNodeWithTag(NEW_AGENT_TAG, useUnmergedTree = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(PICKER_SHEET_TAG), timeoutMillis = SHEET_TIMEOUT_MS)

        composeRule.onNodeWithTag(PICKER_TERMINAL_TAG, useUnmergedTree = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag(AGENT_SCREEN_TAG), timeoutMillis = SHEET_TIMEOUT_MS)
    }

    /** On an offline machine's space, New agent is off: tapping it opens no picker. */
    @Test
    fun aDisabledNewAgentOpensNoPicker() {
        openSpaces()

        openSpaceDetail("lab", "sp_lab")

        composeRule.onNodeWithTag(NEW_AGENT_TAG, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertTrue("no picker opened", composeRule.onAllNodesWithTag(PICKER_SHEET_TAG).fetchSemanticsNodes().isEmpty())
    }
}
