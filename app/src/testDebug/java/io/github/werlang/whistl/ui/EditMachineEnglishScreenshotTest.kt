package io.github.werlang.whistl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The English frames of the Edit machine sheet (phase 7 P2): the first demo machine, opened from its card menu and prefilled.
 * Recorded under `app/src/test/screenshots/` in dark and light, as `edit-machine_<theme>_en.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditMachineEnglishScreenshotTest : ScreenHarness() {
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
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun editMachineSheet() {
        render(ShotScreen.EditMachine)
    }

    private companion object {
        /** Dark and light. */
        val DARK_LIGHT = listOf(ShotVariant(dark = true), ShotVariant(dark = false))

        /** The locale token of the English file names. */
        const val LOCALE: String = "en"
    }
}
