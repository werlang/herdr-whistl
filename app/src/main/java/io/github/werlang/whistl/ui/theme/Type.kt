package io.github.werlang.whistl.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.werlang.whistl.R

/**
 * JetBrains Mono, bundled (OFL), for addresses, paths, keys and the terminal.
 * Used by later screens; the shell does not render monospace text yet.
 */
val WhistlMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * Material 3 type scale for the UI. Its default text styles use `FontFamily.Default`,
 * the system sans (SPEC §3).
 */
val WhistlTypography: Typography = Typography()
