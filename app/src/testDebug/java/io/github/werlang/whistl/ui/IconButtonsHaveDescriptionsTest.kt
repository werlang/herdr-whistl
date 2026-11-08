package io.github.werlang.whistl.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guard (phase 4 r2 §9b, phase 4b r1 §9): on every screen and sheet the suite renders, every clickable node that has no text
 * has a non-empty content description, and every clickable node is at least 48 dp in both dimensions (SPEC §3). Lint does not
 * check either rule for Compose.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class IconButtonsHaveDescriptionsTest : ScreenHarness() {

    /**
     * Shows [screen] and checks every clickable node on it.
     *
     * @param screen the screen, with its sheet or filter open.
     */
    private fun checkScreen(screen: ShotScreen) {
        showForGuard(screen)
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
                "${screen.slug}: clickable node #$index is ${widthDp}x${heightDp} dp, below $MIN_TARGET_DP",
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

    @Test
    fun machinesNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.Machines)

    @Test
    fun machinesEmptyNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.MachinesEmpty)

    @Test
    fun machinesLongNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.MachinesLong)

    @Test
    fun addMachineKeyNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AddMachineKey)

    @Test
    fun addMachinePasswordNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.AddMachinePassword)

    @Test
    fun hostKeyUnknownNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.HostKeyUnknown)

    @Test
    fun hostKeyChangedNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.HostKeyChanged)

    @Test
    fun machineMenuNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.MachineMenu)

    @Test
    fun removeMachineConfirmNodesAreNamedAndLargeEnough() = checkScreen(ShotScreen.RemoveMachineConfirm)

    private companion object {
        /** The minimum touch target in dp (SPEC §3). */
        const val MIN_TARGET_DP: Float = 48f
    }
}
