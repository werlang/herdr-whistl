package io.github.werlang.whistl.ui

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import io.github.werlang.whistl.ui.agent.AGENT_OUTPUT_TAG
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Scrolling a full-screen program (0.7.2): a frame with no history has nothing above it, so a drag has to page the program
 * itself — the pane has nothing to move. Claude Code showed the other reading: the drag scrolled a stale pane and the
 * transcript never moved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentScrollTest : ScreenHarness() {

    /** A drag up on a program with no history presses Page Down, and a drag down presses Page Up. */
    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun aDragPagesTheProgram() {
        showForGuard(ShotScreen.AgentWorking)

        composeRule.onNodeWithTag(AGENT_OUTPUT_TAG, useUnmergedTree = true).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertTrue("Page Down was never pressed", hasLine("keys: pagedown"))

        composeRule.onNodeWithTag(AGENT_OUTPUT_TAG, useUnmergedTree = true).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue("Page Up was never pressed", hasLine("keys: pageup"))
    }

    /** Whether a line of the terminal holds [text]. */
    private fun hasLine(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
}
