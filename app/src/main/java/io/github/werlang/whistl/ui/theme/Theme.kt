package io.github.werlang.whistl.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Material 3 colour scheme for the dark theme, built from the SPEC §3 dark tokens. */
val WhistlDarkScheme: ColorScheme = darkColorScheme(
    primary = WhistlDarkAccent,
    onPrimary = WhistlDarkOnAccent,
    primaryContainer = WhistlDarkSurface,
    onPrimaryContainer = WhistlDarkOnSurface,
    inversePrimary = WhistlLightAccent,
    secondary = WhistlDarkMuted,
    onSecondary = WhistlDarkBackground,
    secondaryContainer = WhistlDarkSurface,
    onSecondaryContainer = WhistlDarkOnSurface,
    tertiary = WhistlDarkWorking,
    onTertiary = WhistlDarkBackground,
    tertiaryContainer = WhistlDarkSurface,
    onTertiaryContainer = WhistlDarkOnSurface,
    background = WhistlDarkBackground,
    onBackground = WhistlDarkOnSurface,
    surface = WhistlDarkSurface,
    onSurface = WhistlDarkOnSurface,
    surfaceVariant = WhistlDarkField,
    onSurfaceVariant = WhistlDarkMuted,
    surfaceTint = WhistlDarkSurface,
    inverseSurface = WhistlDarkOnSurface,
    inverseOnSurface = WhistlDarkBackground,
    error = WhistlDarkDanger,
    onError = WhistlDarkBackground,
    errorContainer = WhistlDarkSurface,
    onErrorContainer = WhistlDarkDanger,
    outline = WhistlDarkOutline,
    outlineVariant = WhistlDarkOutline,
    scrim = Color.Black,
    surfaceBright = WhistlDarkSurface,
    surfaceDim = WhistlDarkBackground,
    surfaceContainerLowest = WhistlDarkField,
    surfaceContainerLow = WhistlDarkBackground,
    surfaceContainer = WhistlDarkSurface,
    surfaceContainerHigh = WhistlDarkSurface,
    surfaceContainerHighest = WhistlDarkOutline,
)

/** Material 3 colour scheme for the light theme, built from the SPEC §3 light values. */
val WhistlLightScheme: ColorScheme = lightColorScheme(
    primary = WhistlLightAccent,
    onPrimary = WhistlLightOnAccent,
    primaryContainer = WhistlLightSurface,
    onPrimaryContainer = WhistlLightOnSurface,
    inversePrimary = WhistlDarkAccent,
    secondary = WhistlLightMuted,
    onSecondary = WhistlLightBackground,
    secondaryContainer = WhistlLightSurface,
    onSecondaryContainer = WhistlLightOnSurface,
    tertiary = WhistlLightWorking,
    onTertiary = WhistlLightBackground,
    tertiaryContainer = WhistlLightSurface,
    onTertiaryContainer = WhistlLightOnSurface,
    background = WhistlLightBackground,
    onBackground = WhistlLightOnSurface,
    surface = WhistlLightSurface,
    onSurface = WhistlLightOnSurface,
    surfaceVariant = WhistlLightField,
    onSurfaceVariant = WhistlLightMuted,
    surfaceTint = WhistlLightSurface,
    inverseSurface = WhistlLightOnSurface,
    inverseOnSurface = WhistlLightBackground,
    error = WhistlLightDanger,
    onError = WhistlLightSurface,
    errorContainer = WhistlLightSurface,
    onErrorContainer = WhistlLightDanger,
    outline = WhistlLightOutline,
    outlineVariant = WhistlLightOutline,
    scrim = Color.Black,
    surfaceBright = WhistlLightSurface,
    surfaceDim = WhistlLightBackground,
    surfaceContainerLowest = WhistlLightSurface,
    surfaceContainerLow = WhistlLightBackground,
    surfaceContainer = WhistlLightSurface,
    surfaceContainerHigh = WhistlLightSurface,
    surfaceContainerHighest = WhistlLightField,
)

/**
 * Herdr Whistl theme: Material 3 colours, typography and shapes, plus [LocalWhistlTokens].
 *
 * Dark-first: [darkTheme] defaults to `true`, so the app renders dark regardless of the
 * system setting until the Settings theme choice (System / Dark / Light) lands.
 *
 * @param darkTheme whether to use the dark scheme.
 * @param content the composable content to theme.
 */
@Composable
fun WhistlTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) WhistlDarkScheme else WhistlLightScheme
    val tokens = if (darkTheme) DarkWhistlTokens else LightWhistlTokens
    CompositionLocalProvider(LocalWhistlTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WhistlTypography,
            shapes = WhistlShapes,
            content = content,
        )
    }
}
