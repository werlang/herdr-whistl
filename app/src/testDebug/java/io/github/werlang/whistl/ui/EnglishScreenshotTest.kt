package io.github.werlang.whistl.ui

import io.github.werlang.whistl.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The English screenshots of the six r2 screens (SPEC §8): dark and light, and the 1.3× font-scale variant (dark), recorded
 * under `app/src/test/screenshots/`. Each test asserts that its frames are real, that dark and light differ, and that two
 * different screens differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishScreenshotTest : ScreenHarness() {

    /** A screen in dark, light and at 1.3× font scale (dark). */
    private fun checkThreeVariants(screen: ShotScreen): Map<ShotVariant, ByteArray> {
        val frames = renderVariants(
            screen,
            listOf(ShotVariant(dark = true), ShotVariant(dark = false), ShotVariant(dark = true, fontScale = 1.3f)),
            locale = "en",
        )
        val dark = frames.getValue(ShotVariant(dark = true))
        val light = frames.getValue(ShotVariant(dark = false))
        assertTrue("${screen.slug} dark frame is ${dark.size} bytes", dark.size > MIN_PNG_BYTES)
        assertTrue("${screen.slug} light frame is ${light.size} bytes", light.size > MIN_PNG_BYTES)
        assertFalse("${screen.slug}: dark and light are identical", dark.contentEquals(light))
        return frames
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentsAll() {
        checkThreeVariants(ShotScreen.AgentsAll)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentsNeedsYou() {
        checkThreeVariants(ShotScreen.AgentsNeedsYou)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentWorking() {
        checkThreeVariants(ShotScreen.AgentWorking)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentBlocked() {
        checkThreeVariants(ShotScreen.AgentBlocked)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentColour() {
        checkThreeVariants(ShotScreen.AgentColour)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun agentAltScreen() {
        checkThreeVariants(ShotScreen.AgentAltScreen)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun settings() {
        checkThreeVariants(ShotScreen.Settings)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun addKeySheet() {
        checkThreeVariants(ShotScreen.AddKeySheet)
    }

    /** Two different screens render differently: the Agents list and Settings. */
    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun twoDifferentScreensRenderDifferently() {
        val agents = renderVariants(ShotScreen.AgentsAll, listOf(ShotVariant(dark = true)), locale = "en")
            .getValue(ShotVariant(dark = true))
        // The same activity: the Settings tab is tapped, as a user would.
        clickByText(RuntimeEnvironment.getApplication().getString(R.string.destination_settings))
        composeRule.waitForIdle()
        val settings = frameBytes()

        assertNotEquals(agents.toList(), settings.toList())
    }
}
