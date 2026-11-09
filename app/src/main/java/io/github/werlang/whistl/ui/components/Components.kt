package io.github.werlang.whistl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/** The minimum touch target (SPEC §3): every clickable control is at least this tall and wide. */
val MinTouchTarget = 48.dp

/**
 * The bottom sheet's drag handle, at its shipped 32x4 dp look. Material3 1.4.0 makes the handle clickable (tap to expand,
 * collapse or dismiss) and gives it only 32 dp of width, under the 48 dp target (SPEC §3). The handle is drawn inside a
 * 48x48 dp box, centred, so the pill stays where it was and the target is 48 dp.
 */
/**
 * The filled-button colours the app shipped with (material3 1.3.1). Material3 1.4.0 renders a disabled button with the
 * `onSurfaceVariant` label at 38% and a 10% container; 1.3.1 used the `onSurface` label at 38% and a 12% container. Every
 * filled button goes through here so the disabled look stays the shipped one.
 *
 * @param containerColor the enabled container colour (the primary colour by default).
 * @param contentColor the enabled label colour (`onPrimary` by default).
 * @return the button colours.
 */
@Composable
fun whistlButtonColors(
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = containerColor,
    contentColor = contentColor,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDragHandle() {
    Box(modifier = Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
    }
}

/**
 * A status as text beside a coloured dot. The label is always shown: status is never colour-only (SPEC §3).
 *
 * @param label the status text, already translated.
 * @param color the dot's colour.
 * @param modifier the modifier for the row.
 */
@Composable
fun StatusLabel(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A segmented choice: one option of a row of options, selected or not. Touch target: 48 dp.
 *
 * @param label the option's text.
 * @param selected whether it is the current choice.
 * @param enabled whether it can be tapped.
 * @param onClick called when tapped.
 * @param modifier the modifier for the option.
 */
@Composable
fun SegmentOption(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalWhistlTokens.current
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary else tokens.field)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * A filter chip with its count. Touch target: 48 dp.
 *
 * @param label the chip's text.
 * @param count how many items it counts.
 * @param selected whether it is the active filter.
 * @param onClick called when tapped.
 */
@Composable
fun CountChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Text(text = count.toString(), style = MaterialTheme.typography.labelMedium)
            }
        },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    )
}

