package io.github.werlang.whistl.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design roles that Material 3's ColorScheme has no slot for (SPEC §3).
 *
 * Read with `LocalWhistlTokens.current`; [WhistlTheme] provides the set that
 * matches the active theme.
 *
 * @property field inputs and segmented tracks.
 * @property terminal terminal screen background (dark in both themes).
 * @property muted secondary text.
 * @property dim tertiary text.
 * @property working "working" status colour.
 * @property blocked "blocked" status colour and badges.
 * @property done "done" and connected status colour.
 * @property idle "idle" and offline status colour.
 */
@Immutable
data class WhistlTokens(
    val field: Color,
    val terminal: Color,
    val muted: Color,
    val dim: Color,
    val working: Color,
    val blocked: Color,
    val done: Color,
    val idle: Color,
)

/** Extra tokens for the dark theme. */
val DarkWhistlTokens = WhistlTokens(
    field = WhistlDarkField,
    terminal = WhistlTerminal,
    muted = WhistlDarkMuted,
    dim = WhistlDarkDim,
    working = WhistlDarkWorking,
    blocked = WhistlDarkBlocked,
    done = WhistlDarkDone,
    idle = WhistlDarkIdle,
)

/** Extra tokens for the light theme. */
val LightWhistlTokens = WhistlTokens(
    field = WhistlLightField,
    terminal = WhistlTerminal,
    muted = WhistlLightMuted,
    dim = WhistlLightDim,
    working = WhistlLightWorking,
    blocked = WhistlLightBlocked,
    done = WhistlLightDone,
    idle = WhistlLightIdle,
)

/** Provides [WhistlTokens] to the composition. Defaults to the dark set. */
val LocalWhistlTokens = staticCompositionLocalOf { DarkWhistlTokens }
