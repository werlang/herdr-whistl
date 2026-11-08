package io.github.werlang.whistl

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.werlang.whistl.data.ThemeChoice
import io.github.werlang.whistl.platform.AppCompatLanguageApplier
import io.github.werlang.whistl.ui.LocalAppContainer
import io.github.werlang.whistl.ui.WhistlApp
import io.github.werlang.whistl.ui.theme.WhistlTheme

/**
 * The single activity of Herdr Whistl. It is an `AppCompatActivity` so the app language can be set below Android 13. The
 * theme follows the Settings choice (System, Dark or Light), and the system bars follow the effective theme: light icons on a
 * dark theme, dark icons on a light one.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WhistlApplication).container
        AppCompatLanguageApplier.apply(container.settings.settings.value.language)
        setContent {
            val prefs by container.settings.settings.collectAsState()
            val dark = when (prefs.theme) {
                ThemeChoice.System -> isSystemInDarkTheme()
                ThemeChoice.Dark -> true
                ThemeChoice.Light -> false
            }
            SystemBars(activity = this@MainActivity, dark = dark)
            CompositionLocalProvider(LocalAppContainer provides container) {
                WhistlTheme(darkTheme = dark) {
                    WhistlApp()
                }
            }
        }
    }
}

/**
 * Sets the edge-to-edge system bars for the effective theme: light icons on a dark theme, dark icons on a light one.
 *
 * @param activity the activity whose window is styled.
 * @param dark whether the effective theme is dark.
 */
@Composable
private fun SystemBars(activity: ComponentActivity, dark: Boolean) {
    DisposableEffect(dark) {
        val bar = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        onDispose {}
    }
}
