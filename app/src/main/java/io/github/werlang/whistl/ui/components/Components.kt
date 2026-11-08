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

/**
 * Padding that keeps a control's text clear of the screen edge (16 dp gutter, SPEC §3).
 *
 * @return the modifier.
 */
fun Modifier.screenGutter(): Modifier = this.padding(horizontal = 16.dp)
