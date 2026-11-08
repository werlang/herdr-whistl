package io.github.werlang.whistl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Portuguese screenshots of the three sheets the SPEC §2 matrix had no frame for: the Agent options sheet, the open-source
 * licences sheet, and a machine's unknown host key shown as its card sheet (phase 4b r2, §1 of the brief: every screen and sheet
 * of SPEC §2). Recorded under `app/src/test/screenshots/` in dark and light.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetsPortugueseScreenshotTest : ScreenHarness() {

    /** Renders [screen] in dark and light; each frame is real, and dark and light differ. */
    private fun render(screen: ShotScreen): Map<ShotVariant, ByteArray> {
        val frames = renderVariants(screen, DARK_LIGHT, locale = LOCALE)
        frames.forEach { (variant, png) ->
            assertTrue("${screen.slug} ${variant.theme} frame is ${png.size} bytes", png.size > MIN_PNG_BYTES)
        }
        assertFalse("${screen.slug}: dark and light are identical", frames.getValue(DARK_LIGHT[0]).contentEquals(frames.getValue(DARK_LIGHT[1])))
        return frames
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun agentMenu() {
        render(ShotScreen.AgentMenu)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun licencesSheet() {
        render(ShotScreen.LicencesSheet)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun machineHostKeySheet() {
        render(ShotScreen.MachineHostKeySheet)
    }

    private companion object {
        /** Dark and light. */
        val DARK_LIGHT = listOf(ShotVariant(dark = true), ShotVariant(dark = false))

        /** The locale token of the English file names. */
        const val LOCALE: String = "pt-BR"
    }
}
