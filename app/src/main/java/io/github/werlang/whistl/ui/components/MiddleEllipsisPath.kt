package io.github.werlang.whistl.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/** The glyph that stands in for the collapsed middle segments of a path. */
private const val MiddleEllipsis: String = "…"

/** A path's separator, kept out of string templates because it is not UI copy. */
private const val PathSeparator: String = "/"

/**
 * Joins a leading part, the ellipsis, and the last segment into a collapsed path.
 *
 * @param head the leading segments to keep, or an empty string for none.
 * @param last the last segment, always kept.
 * @param absolute whether the original path started with `/`.
 * @return the collapsed path.
 */
private fun collapsedPath(head: String, last: String, absolute: Boolean): String {
    val marker = if (head.isEmpty()) MiddleEllipsis else head + PathSeparator + MiddleEllipsis
    val rooted = if (absolute) PathSeparator + marker else marker
    return rooted + PathSeparator + last
}

/**
 * Shortens [path] in the middle until [fits] accepts it, keeping the last segment that names the folder.
 *
 * A path that already fits is returned unchanged. Otherwise the segments before the last one are collapsed from the right,
 * one at a time, and the first candidate [fits] accepts is returned: `/home/demo/work/nightly-build` becomes
 * `/home/demo/…/nightly-build`, then `/home/…/nightly-build`, then `/…/nightly-build`, then `…/nightly-build`; `~/a/b/c`
 * becomes `~/a/…/c`, then `~/…/c`, then `…/c`.
 *
 * When not even `…/<last>` fits, the last segment is shortened from the left until a suffix fits after the ellipsis; a path
 * with no separator keeps a suffix of its only segment after the ellipsis. `…` is returned when nothing else fits. A trailing
 * `/` is ignored when the last segment is found, and is not part of the shortened result.
 *
 * @param path the path to shorten.
 * @param fits whether a candidate path fits the available width.
 * @return the original path when it fits, the longest candidate [fits] accepts, or the best fallback.
 */
fun middleEllipsizePath(path: String, fits: (String) -> Boolean): String {
    if (fits(path)) return path

    val trimmed = path.trimEnd('/')
    val lastSlash = trimmed.lastIndexOf('/')
    if (lastSlash < 0) {
        // No separator: the whole path is one segment, so only a suffix of it can be kept.
        if (trimmed.isEmpty()) return MiddleEllipsis
        for (length in trimmed.length downTo 1) {
            val candidate = MiddleEllipsis + trimmed.takeLast(length)
            if (fits(candidate)) return candidate
        }
        return MiddleEllipsis
    }

    val last = trimmed.substring(lastSlash + 1)
    if (last.isEmpty()) return MiddleEllipsis
    val absolute = trimmed.startsWith('/')
    val body = if (absolute) trimmed.substring(1) else trimmed
    val segments = body.split('/').filter { it.isNotEmpty() }
    val intermediates = segments.dropLast(1)

    // Keep fewer and fewer of the leading segments; the last segment always stays.
    for (keep in intermediates.size - 1 downTo 0) {
        val candidate = collapsedPath(intermediates.take(keep).joinToString(PathSeparator), last, absolute)
        if (fits(candidate)) return candidate
    }
    val minimal = MiddleEllipsis + PathSeparator + last
    if (fits(minimal)) return minimal

    // Neither the collapsed path nor its shortest structured form fits: keep a suffix of the last segment.
    for (length in last.length - 1 downTo 1) {
        val candidate = MiddleEllipsis + PathSeparator + last.takeLast(length)
        if (fits(candidate)) return candidate
    }
    return MiddleEllipsis
}

/**
 * A path shortened in the middle when it does not fit its width, keeping its last segment visible (proposal 15).
 *
 * The text is measured with the same [style] the [Text] renders with, one line and no wrapping, against the width the layout
 * gives this composable. If even the shortened path does not fit, the [Text] ellipsizes its end as a last resort.
 *
 * @param path the path to show.
 * @param style the text style to render and measure with.
 * @param color the text colour.
 * @param modifier the modifier for the box that bounds the path's width.
 */
@Composable
fun MiddleEllipsisPath(
    path: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val shortened = remember(path, style, maxWidthPx, measurer) {
            middleEllipsizePath(path) { candidate ->
                measurer.measure(
                    text = AnnotatedString(candidate),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= maxWidthPx
            }
        }
        Text(
            text = shortened,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
