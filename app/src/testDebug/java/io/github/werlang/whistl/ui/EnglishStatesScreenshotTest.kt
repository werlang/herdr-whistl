package io.github.werlang.whistl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The English goldens for the states the phase 6 suite did not cover (phase 7 r6, row 11): the Agents filters, the empty
 * Agents list, the Agent notices, the Add machine stages, the Machines problem cards, the Edit sheet in password mode, the
 * Space detail states and the unreadable-home space. Each is recorded under `app/src/test/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishStatesScreenshotTest : ScreenHarness() {

    /** A screen in dark and light, each frame real and the two different. */
    private fun checkTwoThemes(screen: ShotScreen) {
        val frames = renderVariants(screen, listOf(ShotVariant(dark = true), ShotVariant(dark = false)), locale = "en")
        val dark = frames.getValue(ShotVariant(dark = true))
        val light = frames.getValue(ShotVariant(dark = false))
        assertTrue("${screen.slug} dark frame is ${dark.size} bytes", dark.size > MIN_PNG_BYTES)
        assertTrue("${screen.slug} light frame is ${light.size} bytes", light.size > MIN_PNG_BYTES)
        assertFalse("${screen.slug}: dark and light are identical", dark.contentEquals(light))
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentsWorking() {
        checkTwoThemes(ShotScreen.AgentsWorking)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentsIdle() {
        checkTwoThemes(ShotScreen.AgentsIdle)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentsEmpty() {
        checkTwoThemes(ShotScreen.AgentsEmpty)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentFinished() {
        checkTwoThemes(ShotScreen.AgentFinished)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentOffline() {
        checkTwoThemes(ShotScreen.AgentOffline)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun addMachineConnecting() {
        checkTwoThemes(ShotScreen.AddMachineConnecting)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun addMachineRefused() {
        checkTwoThemes(ShotScreen.AddMachineRefused)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun addMachineCoreMissing() {
        checkTwoThemes(ShotScreen.AddMachineCoreMissing)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun addMachineConnectionRefused() {
        checkTwoThemes(ShotScreen.AddMachineConnectionRefused)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun machinesIssues() {
        checkTwoThemes(ShotScreen.MachinesIssues)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun editMachinePassword() {
        checkTwoThemes(ShotScreen.EditMachinePassword)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun spaceDetailEmpty() {
        checkTwoThemes(ShotScreen.SpaceDetailEmpty)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun spaceDetailMissing() {
        checkTwoThemes(ShotScreen.SpaceDetailMissing)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun spacesUnresolvedHome() {
        checkTwoThemes(ShotScreen.SpacesUnresolvedHome)
    }
}
