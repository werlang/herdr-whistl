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
 * The Portuguese screenshots of the Spaces screens and sheets (phase 4b r2, SPEC §8): recorded under `app/src/test/screenshots/`
 * in dark and light; the dense ones also at 1.3× font scale. Each test asserts that its frames are real and that dark and light
 * differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpacesPortugueseScreenshotTest : ScreenHarness() {

    /** Renders [screen] in [variants] and checks each frame is real, and that dark and light differ when both are shown. */
    private fun render(screen: ShotScreen, variants: List<ShotVariant>): Map<ShotVariant, ByteArray> {
        val frames = renderVariants(screen, variants, locale = LOCALE)
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
    fun spaces() {
        render(ShotScreen.Spaces, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spacesEmpty() {
        render(ShotScreen.SpacesEmpty, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spacesLong() {
        render(ShotScreen.SpacesLong, listOf(ShotVariant(dark = true, fontScale = 1.3f)))
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun newSpace() {
        render(ShotScreen.NewSpace, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun newSpaceNoMachine() {
        render(ShotScreen.NewSpaceNoMachine, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spaceMenu() {
        render(ShotScreen.SpaceMenu, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun renameSpace() {
        render(ShotScreen.RenameSpace, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun closeSpaceConfirm() {
        render(ShotScreen.CloseSpaceConfirm, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spaceDetail() {
        render(ShotScreen.SpaceDetail, DARK_LIGHT_LARGE)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun spaceDetailOffline() {
        render(ShotScreen.SpaceDetailOffline, DARK_LIGHT)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun newAgentPicker() {
        render(ShotScreen.NewAgentPicker, DARK_LIGHT_LARGE)
    }

    /** The Spaces list and the Settings screen render differently: a different screen, a different frame. */
    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun twoDifferentScreensRenderDifferently() {
        val spaces = renderVariants(ShotScreen.Spaces, listOf(ShotVariant(dark = true)), locale = LOCALE)
            .getValue(ShotVariant(dark = true))
        // The same activity: the Settings tab is tapped, as a user would.
        clickByText(RuntimeEnvironment.getApplication().getString(R.string.destination_settings))
        composeRule.waitForIdle()
        val settings = frameBytes()

        assertNotEquals(spaces.toList(), settings.toList())
    }

    private companion object {
        /** Dark and light, as the matrix needs them for every screen. */
        val DARK_LIGHT = listOf(ShotVariant(dark = true), ShotVariant(dark = false))

        /** Dark, light, and dark at 1.3× font scale, for the dense screens. */
        val DARK_LIGHT_LARGE = listOf(ShotVariant(dark = true), ShotVariant(dark = false), ShotVariant(dark = true, fontScale = 1.3f))

        /** The locale token of the Portuguese file names. */
        const val LOCALE: String = "pt-BR"
    }
}
