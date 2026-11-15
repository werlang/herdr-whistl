package io.github.werlang.whistl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.werlang.whistl.platform.AgentWatchService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        // The watch runs while the app is alive: it is what makes a notification possible after the screen is gone
        // (0.7.0). It is started here, where the user has just asked for the app, and asked for notifications.
        if (container.settings.settings.value.notifyFinished) requestNotifications()
        lifecycleScope.launch {
            container.settings.settings.collect { prefs ->
                if (prefs.notifyFinished) {
                    AgentWatchService.start(this@MainActivity)
                } else {
                    AgentWatchService.stop(this@MainActivity)
                }
            }
        }
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

    /**
     * Asks for the notification permission on the versions that require asking. A refusal is not an error: the app works,
     * the user is simply not told (0.7.0).
     */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
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
