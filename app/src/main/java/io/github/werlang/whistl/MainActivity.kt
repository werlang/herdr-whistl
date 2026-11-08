package io.github.werlang.whistl

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.werlang.whistl.ui.WhistlApp
import io.github.werlang.whistl.ui.theme.WhistlTheme

/**
 * The single activity of Herdr Whistl: edge-to-edge, dark-first Compose content.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-first until the Settings theme choice lands: light bar icons in both system modes.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            WhistlTheme {
                WhistlApp()
            }
        }
    }
}
