package io.github.werlang.whistl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Portuguese (pt-BR) screenshots of the six r2 screens, dark and light (SPEC §8), recorded under
 * `app/src/test/screenshots/`. The locale comes from the Robolectric qualifier, so the pt-BR resources are the ones on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortugueseScreenshotTest : ScreenHarness() {

    /** A screen in dark and light. */
    private fun checkTwoThemes(screen: ShotScreen) {
        val frames = renderVariants(screen, listOf(ShotVariant(dark = true), ShotVariant(dark = false)), locale = "pt-BR")
        val dark = frames.getValue(ShotVariant(dark = true))
        val light = frames.getValue(ShotVariant(dark = false))
        assertTrue("${screen.slug} dark frame is ${dark.size} bytes", dark.size > MIN_PNG_BYTES)
        assertTrue("${screen.slug} light frame is ${light.size} bytes", light.size > MIN_PNG_BYTES)
        assertFalse("${screen.slug}: dark and light are identical", dark.contentEquals(light))
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentsAll() {
        checkTwoThemes(ShotScreen.AgentsAll)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentsNeedsYou() {
        checkTwoThemes(ShotScreen.AgentsNeedsYou)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentWorking() {
        checkTwoThemes(ShotScreen.AgentWorking)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentBlocked() {
        checkTwoThemes(ShotScreen.AgentBlocked)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun settings() {
        checkTwoThemes(ShotScreen.Settings)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addKeySheet() {
        checkTwoThemes(ShotScreen.AddKeySheet)
    }

    /** The Settings screen in dark at 1.3× font scale (phase 7 r6, row 12: `settings_dark_pt-BR_fs13.png`). */
    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun settingsLargeFont() {
        val frames = renderVariants(ShotScreen.Settings, listOf(ShotVariant(dark = true, fontScale = 1.3f)), locale = "pt-BR")
        val frame = frames.getValue(ShotVariant(dark = true, fontScale = 1.3f))
        assertTrue("settings large-font frame is ${frame.size} bytes", frame.size > MIN_PNG_BYTES)
    }
}
