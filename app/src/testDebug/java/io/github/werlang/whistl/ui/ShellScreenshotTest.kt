package io.github.werlang.whistl.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.theme.WhistlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Screenshot tests for the existing app shell (SPEC §8, phase 4 r1): [WhistlApp] in dark and light, English and
 * Portuguese (pt-BR), on a fixed device size.
 *
 * Each locale test renders both themes. Each capture is written by `captureRoboImage` (recorded by
 * `scripts/gradle recordRoborazziDebug`, compared by `verifyRoborazziDebug`), and is also encoded here as PNG bytes so
 * the test asserts on it directly: a PNG under [MIN_PNG_BYTES] is a blank or nearly blank frame, and the dark and light
 * frames must differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The shell in English, dark then light. */
    @Test
    @Config(qualifiers = "en-rUS-$DEVICE")
    fun englishShellRendersInDarkAndLight() {
        assertEquals("Machines", RuntimeEnvironment.getApplication().getString(R.string.machines_title))
        renderBothThemes("shell_%s_en")
    }

    /** The same shell in Portuguese (pt-BR): the pt-BR resources are the ones on screen. */
    @Test
    @Config(qualifiers = "pt-rBR-$DEVICE")
    fun portugueseShellRendersInDarkAndLight() {
        assertEquals("Máquinas", RuntimeEnvironment.getApplication().getString(R.string.machines_title))
        renderBothThemes("shell_%s_pt")
    }

    /**
     * Renders [WhistlApp] in dark, captures it, switches to light, captures again, and checks both frames.
     *
     * @param nameTemplate the file name with `%s` for `dark` or `light`.
     */
    private fun renderBothThemes(nameTemplate: String) {
        val darkTheme = mutableStateOf(true)
        composeRule.setContent {
            WhistlTheme(darkTheme = darkTheme.value) {
                WhistlApp()
            }
        }
        val dark = capture(nameTemplate.format("dark"))
        composeRule.runOnIdle { darkTheme.value = false }
        val light = capture(nameTemplate.format("light"))

        assertTrue("dark frame is blank: ${dark.size} bytes", dark.size > MIN_PNG_BYTES)
        assertTrue("light frame is blank: ${light.size} bytes", light.size > MIN_PNG_BYTES)
        assertFalse("dark and light frames are identical", dark.contentEquals(light))
    }

    /**
     * Captures the whole screen: writes the Roborazzi file under [name] (recorded or verified by the Gradle tasks), and
     * returns the window's drawn frame as PNG bytes for the assertions.
     *
     * The frame is drawn from the decor view, not with `captureToImage`: under Robolectric that call waits for a redraw
     * that never comes.
     *
     * @param name the file name, for example `shell_dark_en.png`.
     * @return the PNG bytes of the frame.
     */
    private fun capture(name: String): ByteArray {
        composeRule.waitForIdle()
        val file = "$name.png"
        composeRule.onRoot().captureRoboImage(file)
        val view = composeRule.activity.window.decorView
        var png = ByteArray(0)
        composeRule.runOnUiThread {
            val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frame))
            png = ByteArrayOutputStream().use { out ->
                check(frame.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed for $file" }
                out.toByteArray()
            }
        }
        return png
    }

    private companion object {
        /** The fixed device size (a 411 × 891 dp phone at xxhdpi), so every capture has the same frame. */
        const val DEVICE = "w411dp-h891dp-xxhdpi"

        /**
         * The size floor from the phase brief (a few KiB). It is a sanity check only: a uniform frame at this device size
         * can also compress to a few KiB, so the content check is the dark-versus-light comparison.
         */
        const val MIN_PNG_BYTES = 4 * 1024

        /** PNG is lossless; the quality argument is ignored by the encoder. */
        const val PNG_QUALITY = 100
    }
}
