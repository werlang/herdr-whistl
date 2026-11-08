package io.github.werlang.whistl.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.settings.ADD_KEY_SHEET_TAG
import io.github.werlang.whistl.ui.components.MinTouchTarget
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guard (phase 4 r2 §9b): on every r2 screen, every clickable node that has no text has a non-empty content description, and
 * every clickable node is at least 48 dp in both dimensions (SPEC §3). Lint does not check either rule for Compose.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class IconButtonsHaveDescriptionsTest : ScreenHarness() {

    /** Renders [screen], opens its sheet if it has one, and checks every clickable node on it. */
    private fun checkScreen(screen: ShotScreen) {
        val container = demoContainer()
        composeRule.setContent {
            ShotContent(container = container, route = screen.route(), dark = true, fontScale = 1f)
        }
        composeRule.waitForIdle()
        when (screen) {
            ShotScreen.AgentsNeedsYou -> composeRule.onNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.agents_filter_needs_you), substring = true) and hasClickAction()).performClick()
            ShotScreen.AddKeySheet -> composeRule.onNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.settings_add_key), substring = true) and hasClickAction())
                .performScrollTo()
                .performClick()
            else -> Unit
        }
        composeRule.waitForIdle()
        if (screen == ShotScreen.AddKeySheet) {
            composeRule.waitUntilAtLeastOneExists(hasTestTag(ADD_KEY_SHEET_TAG), timeoutMillis = 5_000L)
        }
        val nodes = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("${screen.slug}: no clickable node found", nodes.isNotEmpty())
        val density = SHOT_DENSITY
        nodes.forEachIndexed { index, node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
            val named = !text.isNullOrEmpty() || !description.isNullOrEmpty()
            assertTrue("${screen.slug}: clickable node #$index has neither text nor a content description", named)
            if (text.isNullOrEmpty()) {
                assertTrue(
                    "${screen.slug}: clickable node #$index has no text, so it needs a content description",
                    !description.isNullOrEmpty(),
                )
            }
            val widthDp = node.size.width / density
            val heightDp = node.size.height / density
            assertTrue(
                "${screen.slug}: clickable node #$index is ${widthDp}x${heightDp} dp, below $MinTouchTarget",
                widthDp >= MIN_TARGET_DP && heightDp >= MIN_TARGET_DP,
            )
        }
    }

    @Test
    fun agentsAllNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AgentsAll)

    @Test
    fun agentsNeedsYouNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AgentsNeedsYou)

    @Test
    fun agentWorkingNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AgentWorking)

    @Test
    fun agentBlockedNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AgentBlocked)

    @Test
    fun settingsNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.Settings)

    @Test
    fun addKeySheetNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AddKeySheet)

    private companion object {
        /** The minimum touch target in dp (SPEC §3). */
        const val MIN_TARGET_DP: Float = 48f
    }
}
