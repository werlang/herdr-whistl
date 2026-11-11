package io.github.werlang.whistl.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R

/** The cream the launcher icon is drawn on; the mark's own cream must be this value. */
private val MARK_BACKGROUND = androidx.compose.ui.graphics.Color(0xFFF1EFE3)

/**
 * The app mark as a small rounded square, for screen headers (phase 9, SPEC §3).
 *
 * It is deliberately the launcher icon's geometry on the same cream, so the icon in the launcher and the mark in the
 * app are the same drawing at two sizes.
 *
 * @param modifier the modifier for the mark.
 * @param size the square's side.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(MARK_BACKGROUND),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_mark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
