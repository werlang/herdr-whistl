package io.github.werlang.whistl.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.werlang.whistl.AppContainer

/**
 * The app's object graph, provided by the activity (and by the screenshot tests). Screens take their repositories from here,
 * so no screen builds one itself.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer is provided: wrap the content in CompositionLocalProvider(LocalAppContainer provides container)")
}
