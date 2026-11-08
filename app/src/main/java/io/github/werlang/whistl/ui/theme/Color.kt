package io.github.werlang.whistl.ui.theme

import androidx.compose.ui.graphics.Color

// Colour tokens from SPEC §3. Names follow the spec's token table.
// Dark values are the default theme. Light values are the spec's listed roles
// plus the unlisted roles chosen for ≥4.5:1 text contrast on #F7F8F9 (see the
// round evidence). The terminal colour is the same in both themes.

/** Dark background: screens. */
val WhistlDarkBackground = Color(0xFF121417)

/** Dark surface: cards, sheets. */
val WhistlDarkSurface = Color(0xFF1B1E23)

/** Dark outline: card borders, dividers. */
val WhistlDarkOutline = Color(0xFF262A30)

/** Dark field: inputs, segmented tracks. */
val WhistlDarkField = Color(0xFF0D0F11)

/** Terminal screen background; always dark, in both themes. */
val WhistlTerminal = Color(0xFF0A0B0C)

/** Dark primary text. */
val WhistlDarkOnSurface = Color(0xFFE8EAED)

/** Dark secondary text. */
val WhistlDarkMuted = Color(0xFFA5ABB3)

/** Dark tertiary text; ≥4.5:1 on the dark background. */
val WhistlDarkDim = Color(0xFF8A9099)

/** Dark accent: primary actions, selection, tmux bar. */
val WhistlDarkAccent = Color(0xFF5EC2B7)

/** Dark text on accent. */
val WhistlDarkOnAccent = Color(0xFF0B1F1D)

/** Dark "working" status. */
val WhistlDarkWorking = Color(0xFF7FB2FF)

/** Dark "blocked" status and badges. */
val WhistlDarkBlocked = Color(0xFFF5B860)

/** Dark "done" status, connected. */
val WhistlDarkDone = Color(0xFF7BD88F)

/** Dark "idle" status, offline. */
val WhistlDarkIdle = Color(0xFFA5ABB3)

/** Dark destructive actions. */
val WhistlDarkDanger = Color(0xFFFF8A80)

/** Light background, the reference for light contrast. */
val WhistlLightBackground = Color(0xFFF7F8F9)

/** Light surface (unlisted in SPEC; white for cards on #F7F8F9). */
val WhistlLightSurface = Color(0xFFFFFFFF)

/** Light outline (unlisted; non-text border). */
val WhistlLightOutline = Color(0xFFD3D8DE)

/** Light field (unlisted; inputs on the light surface). */
val WhistlLightField = Color(0xFFE9ECEF)

/** Light primary text (unlisted; 16.6:1 on the light background). */
val WhistlLightOnSurface = Color(0xFF16191D)

/** Light secondary text (unlisted; 7.4:1 on the light background). */
val WhistlLightMuted = Color(0xFF4B525C)

/** Light tertiary text (unlisted; 5.7:1 on the light background). */
val WhistlLightDim = Color(0xFF5C636D)

/** Light accent (SPEC §3 light value). */
val WhistlLightAccent = Color(0xFF16786F)

/** Light text on accent (unlisted; 5.3:1 against the light accent). */
val WhistlLightOnAccent = Color(0xFFFFFFFF)

/** Light "working" status (SPEC §3 light value). */
val WhistlLightWorking = Color(0xFF2459B8)

/** Light "blocked" status (SPEC §3 light value). */
val WhistlLightBlocked = Color(0xFF8F5600)

/** Light "done" status (SPEC §3 light value). */
val WhistlLightDone = Color(0xFF24733D)

/** Light "idle" status (unlisted; 5.8:1 on the light background). */
val WhistlLightIdle = Color(0xFF5B626C)

/** Light destructive actions (SPEC §3 light value). */
val WhistlLightDanger = Color(0xFFB3261E)
