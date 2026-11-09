package io.github.werlang.whistl.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pt-BR goldens for the states of [EnglishStatesScreenshotTest] (phase 7 r6, row 11). The Agents states get dark and light,
 * as the phase 6 Agents screens do; the others get dark, the variant every sheet and card has in the phase 6 suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortugueseStatesScreenshotTest : ScreenHarness() {

    /** A screen in dark only: its frame is real. */
    private fun checkDark(screen: ShotScreen) {
        val frame = renderVariants(screen, listOf(ShotVariant(dark = true)), locale = "pt-BR").getValue(ShotVariant(dark = true))
        assertTrue("${screen.slug} dark frame is ${frame.size} bytes", frame.size > MIN_PNG_BYTES)
    }

    /** A screen in dark and light. */
    private fun checkTwoThemes(screen: ShotScreen) {
        val frames = renderVariants(screen, listOf(ShotVariant(dark = true), ShotVariant(dark = false)), locale = "pt-BR")
        assertTrue("${screen.slug} dark frame is ${frames.getValue(ShotVariant(dark = true)).size} bytes", frames.getValue(ShotVariant(dark = true)).size > MIN_PNG_BYTES)
        assertTrue("${screen.slug} light frame is ${frames.getValue(ShotVariant(dark = false)).size} bytes", frames.getValue(ShotVariant(dark = false)).size > MIN_PNG_BYTES)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentsWorking() {
        checkTwoThemes(ShotScreen.AgentsWorking)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentsIdle() {
        checkTwoThemes(ShotScreen.AgentsIdle)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentsEmpty() {
        checkDark(ShotScreen.AgentsEmpty)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentFinished() {
        checkDark(ShotScreen.AgentFinished)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentOffline() {
        checkDark(ShotScreen.AgentOffline)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachineConnecting() {
        checkDark(ShotScreen.AddMachineConnecting)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachineRefused() {
        checkDark(ShotScreen.AddMachineRefused)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachineCoreMissing() {
        checkDark(ShotScreen.AddMachineCoreMissing)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachineConnectionRefused() {
        checkDark(ShotScreen.AddMachineConnectionRefused)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machinesIssues() {
        checkDark(ShotScreen.MachinesIssues)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun editMachinePassword() {
        checkDark(ShotScreen.EditMachinePassword)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spaceDetailEmpty() {
        checkDark(ShotScreen.SpaceDetailEmpty)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spaceDetailMissing() {
        checkDark(ShotScreen.SpaceDetailMissing)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spacesUnresolvedHome() {
        checkDark(ShotScreen.SpacesUnresolvedHome)
    }
}
