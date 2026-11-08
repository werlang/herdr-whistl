package io.github.werlang.whistl.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Pills and chips: fully rounded ends (SPEC §3). */
val WhistlPillShape = RoundedCornerShape(CornerSize(percent = 50))

/** Cards: 14dp radius (SPEC §3). */
val WhistlCardShape = RoundedCornerShape(14.dp)

/** Bottom sheets: 22dp radius on the top edge only (SPEC §3). */
val WhistlSheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** Material shape scale mapped onto the Whistl shapes. */
val WhistlShapes = Shapes(
    small = WhistlPillShape,
    medium = WhistlCardShape,
    large = WhistlCardShape,
    extraLarge = WhistlSheetShape,
)
