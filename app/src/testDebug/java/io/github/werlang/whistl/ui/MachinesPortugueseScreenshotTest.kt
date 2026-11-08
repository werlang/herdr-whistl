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
 * The Portuguese screenshots of the Machines screens and sheets (phase 4b r1, SPEC §8): recorded under `app/src/test/screenshots/`
 * in dark and light, and the dense ones also at 1.3× font scale. Each test asserts that its frames are real and that dark and
 * light differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MachinesPortugueseScreenshotTest : ScreenHarness() {

    /** Renders [screen] in [variants] and checks each frame is real, and that dark and light differ when both are shown. */
    private fun render(screen: ShotScreen, variants: List<ShotVariant>): Map<ShotVariant, ByteArray> {
        val frames = renderVariants(screen, variants, locale = "pt-BR")
        frames.forEach { (variant, png) ->
            assertTrue("${screen.slug} ${variant.theme} frame is ${png.size} bytes", png.size > MIN_PNG_BYTES)
        }
        val dark = frames[ShotVariant(dark = true)]
        val light = frames[ShotVariant(dark = false)]
        if (dark != null && light != null) {
            assertFalse("${screen.slug}: dark and light are identical", dark.contentEquals(light))
        }
        return frames
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machines() {
        render(ShotScreen.Machines, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machinesEmpty() {
        render(ShotScreen.MachinesEmpty, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machinesLong() {
        render(ShotScreen.MachinesLong, listOf(ShotVariant(dark = true, fontScale = 1.3f)))
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachineKey() {
        render(ShotScreen.AddMachineKey, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun addMachinePassword() {
        render(ShotScreen.AddMachinePassword, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun hostKeyUnknown() {
        render(ShotScreen.HostKeyUnknown, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun hostKeyChanged() {
        render(ShotScreen.HostKeyChanged, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machineMenu() {
        render(ShotScreen.MachineMenu, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun removeMachineConfirm() {
        render(ShotScreen.RemoveMachineConfirm, DARK_LIGHT)
    }

    /** The Machines list and the Settings screen render differently: a different screen, a different frame. */
    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun twoDifferentScreensRenderDifferently() {
        val machines = renderVariants(ShotScreen.Machines, listOf(ShotVariant(dark = true)), locale = "pt-BR")
            .getValue(ShotVariant(dark = true))
        // The same activity: the Settings tab is tapped, as a user would.
        clickByText(RuntimeEnvironment.getApplication().getString(R.string.destination_settings))
        composeRule.waitForIdle()
        val settings = frameBytes()

        assertNotEquals(machines.toList(), settings.toList())
    }

    private companion object {
        /** Dark and light, as the matrix needs them for every screen. */
        val DARK_LIGHT = listOf(ShotVariant(dark = true), ShotVariant(dark = false))

        /** Dark, light, and dark at 1.3× font scale, for the dense screens. */
        val DARK_LIGHT_LARGE = listOf(ShotVariant(dark = true), ShotVariant(dark = false), ShotVariant(dark = true, fontScale = 1.3f))

        /** The locale token of the Portuguese file names. */
        const val locale: String = "pt-BR"
    }
}
