package io.github.werlang.whistl.ui.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.domain.CellColor
import io.github.werlang.whistl.domain.CellRun
import io.github.werlang.whistl.domain.CellStyle
import io.github.werlang.whistl.domain.ShortcutBar
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.components.StatusLabel
import io.github.werlang.whistl.ui.components.whistlButtonColors
import io.github.werlang.whistl.ui.keyNameRes
import io.github.werlang.whistl.ui.statusColor
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono
import io.github.werlang.whistl.ui.theme.WhistlTerminalText
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The test tag of the Agent screen, so a navigation test can see that it opened. */
const val AGENT_SCREEN_TAG: String = "agent-screen"

/** The test tag of the terminal output: its lines are separate semantics nodes inside it (phase 7 r6, row 13). */
const val AGENT_OUTPUT_TAG: String = "agent-output"

/** The test tag of the Agent screen's options sheet (Interrupt, Stop and remove). */
const val AGENT_MENU_SHEET_TAG: String = "agent-menu-sheet"

/**
 * The Agent screen (SPEC §2.5): a full-height terminal, the status bar, the shortcut keys in Settings order, the text field and
 * Send. The overflow menu holds Interrupt and Stop and remove; Stop and remove asks first, then closes the screen.
 *
 * @param viewModel the screen's state and events.
 * @param onBack called when the screen closes: the back arrow, or the agent was stopped and removed.
 * @param modifier the modifier for the screen.
 */
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }
    AgentContent(
        state = state,
        onBack = onBack,
        onKey = viewModel::onKey,
        onType = viewModel::onType,
        onBackspace = viewModel::onBackspace,
        onPaste = viewModel::onPaste,
        onOpenMenu = viewModel::onOpenMenu,
        onCloseMenu = viewModel::onCloseMenu,
        onInterrupt = viewModel::onInterrupt,
        onAskStop = viewModel::onAskStop,
        onCancelStop = viewModel::onCancelStop,
        onConfirmStop = viewModel::onConfirmStop,
        onPaneSize = viewModel::onPaneSize,
        modifier = modifier,
    )
}

/** The screen without its view model: what it shows, and the events it reports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentContent(
    state: AgentUiState,
    onBack: () -> Unit,
    onKey: (ShortcutKey) -> Unit,
    onType: (String) -> Unit,
    onBackspace: (Int) -> Unit,
    onPaste: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onInterrupt: () -> Unit,
    onAskStop: () -> Unit,
    onCancelStop: () -> Unit,
    onConfirmStop: () -> Unit,
    onPaneSize: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Tapping the terminal is how a terminal asks for the keyboard; the toggle in the row below does the same.
    var keyboardRequest by remember { mutableStateOf(0) }
    Column(modifier = modifier.fillMaxSize().testTag(AGENT_SCREEN_TAG)) {
        AgentHeader(state = state, onBack = onBack, onOptions = onOpenMenu)
        TerminalOutput(
            state = state,
            onPaneSize = onPaneSize,
            onTap = { keyboardRequest++ },
            modifier = Modifier.weight(1f),
        )
        StatusBar(state = state)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One row: show/hide the keyboard, paste, then the shortcut keys. The keyboard and the clipboard are the
            // two things a thumb reaches for, so they sit before the keys (0.5.1).
            HintLine(state = state)
            NoticeLine(state = state)
            BlockedHint(state = state)
            ActionRow(
                state = state,
                onKey = onKey,
                onType = onType,
                onBackspace = onBackspace,
                onPaste = onPaste,
                keyboardRequest = keyboardRequest,
            )
        }
    }
    if (state.menuOpen) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = onCloseMenu, modifier = Modifier.testTag(AGENT_MENU_SHEET_TAG)) {
            SheetAction(text = stringResource(R.string.agent_interrupt), onClick = onInterrupt)
            SheetAction(text = stringResource(R.string.agent_stop), danger = true, onClick = onAskStop)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (state.confirmStop) {
        ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = onCancelStop) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.agent_stop_title, state.name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.agent_stop_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalWhistlTokens.current.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancelStop, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onConfirmStop,
                        colors = whistlButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.agent_stop_confirm))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The header: back, the name and address, the status with its dot, and the options button. */
@Composable
private fun AgentHeader(state: AgentUiState, onBack: () -> Unit, onOptions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = WhistlMono,
                    color = LocalWhistlTokens.current.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusLabel(
                label = stringResource(statusLabelRes(state.statusLabel)),
                color = when (state.statusLabel) {
                    AgentStatusLabel.Finished, AgentStatusLabel.Offline -> LocalWhistlTokens.current.idle
                    else -> statusColor(state.status)
                },
            )
        }
        IconButton(onClick = onOptions, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.agent_options))
        }
    }
}

/** The terminal's padding inside its box, in dp. */
private val TERMINAL_PADDING: Dp = 12.dp

/** Glyphs measured to find one cell's width: a long run averages out the rounding of a single one. */
private const val CELL_PROBE_GLYPHS: Int = 10

/** How much smaller the terminal font is while the keyboard is open, so the shorter pane still shows readable rows. */
private const val IME_FONT_SCALE: Float = 0.85f

/** The opacity of dim text. */
private const val DIM_ALPHA: Float = 0.6f

/** How much thicker a heavy box line is drawn than a light one. */
private const val HEAVY_STROKE_FACTOR: Float = 2f

/** How many run layouts are kept before the cache is emptied: a screen of history is far below it. */
private const val LAYOUT_CACHE_LIMIT: Int = 6_000

/** How long a pane size must hold before it is sent to the core: a rotation passes through layouts that are not the final size. */
private const val RESIZE_SETTLE_MS: Long = 150L

/** The size of one terminal cell in pixels, and the height of a line. */
private class CellMetrics(val cellWidth: Float, val lineHeight: Float)

/**
 * The terminal (SPEC §2.5): the emulator's cells drawn on a Canvas, in monospace, on the always-dark terminal colour, scrolled to the
 * last line. Each line also carries its text as a node of its own, so a screen reader moves line by line (phase 7 r6, row 13). The
 * pane is measured in cells, and [onPaneSize] receives the size, so the core is told what the viewer shows.
 *
 * @param state the screen's state; its frame is what is drawn.
 * @param onPaneSize called with the pane's size in cells whenever it changes.
 * @param modifier the modifier for the box.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalOutput(
    state: AgentUiState,
    onPaneSize: (Int, Int) -> Unit,
    onTap: () -> Unit = {},
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val description = stringResource(R.string.agent_terminal_description)
    val tokens = LocalWhistlTokens.current
    val density = LocalDensity.current
    // With the keyboard open the pane is much shorter, so the text steps down instead of the TUI scrolling away: the
    // rows that fit are rows you can read at a glance (0.3.5).
    val imeVisible = WindowInsets.isImeVisible
    val fontSize = fontSizeFor(state.fontSize) * if (imeVisible) IME_FONT_SCALE else 1f
    val style = TextStyle(fontFamily = WhistlMono, fontSize = fontSize)
    val measurer = rememberTextMeasurer()
    val metrics = remember(measurer, style) {
        val probe = measurer.measure(AnnotatedString("M".repeat(CELL_PROBE_GLYPHS)), style, softWrap = false).size
        CellMetrics(cellWidth = probe.width.toFloat() / CELL_PROBE_GLYPHS, lineHeight = probe.height.toFloat())
    }
    val layouts = remember(measurer, style) { HashMap<CellRun, TextLayoutResult>() }
    // Layouts of single characters, for the runs that hold geometry: those are drawn character by character.
    val charLayouts = remember(measurer, style) { HashMap<Pair<Char, CellStyle>, TextLayoutResult>() }
    val padding = with(density) { TERMINAL_PADDING.toPx() }
    var pane by remember { mutableStateOf(IntSize.Zero) }
    val frame = state.frame
    LaunchedEffect(frame) { scroll.scrollTo(scroll.maxValue) }
    val cols = ((pane.width - 2 * padding) / metrics.cellWidth).toInt().coerceAtLeast(1)
    val rows = ((pane.height - 2 * padding) / metrics.lineHeight).toInt().coerceAtLeast(1)
    LaunchedEffect(cols, rows, pane) {
        // A pane too small for one cell is not a size. A size still changing (a rotation, the keyboard) is sent only once it has held
        // for RESIZE_SETTLE_MS: the layout passes in between are not reported to the core.
        val holdsACell = pane.width > 2 * padding + metrics.cellWidth && pane.height > 2 * padding + metrics.lineHeight
        if (!holdsACell) return@LaunchedEffect
        delay(RESIZE_SETTLE_MS)
        onPaneSize(cols, rows)
    }
    // The layouts of the runs: a run is measured once while it is on the screen or in the history (its object is kept by the emulator).
    if (layouts.size > LAYOUT_CACHE_LIMIT) layouts.clear()
    frame.lines.forEach { line ->
        line.forEach { run -> layouts.getOrPut(run) { measurer.measure(AnnotatedString(run.text), runStyle(run.style, style), softWrap = false) } }
    }
    val lineHeightDp = with(density) { metrics.lineHeight.toDp() }
    val contentHeightDp = lineHeightDp * frame.lines.size
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.terminal)
            .onSizeChanged { pane = it }
            .verticalScroll(scroll)
            .padding(TERMINAL_PADDING)
            .testTag(AGENT_OUTPUT_TAG)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().height(contentHeightDp)) {
            val top = scroll.value.toFloat() - padding
            val bottom = top + pane.height
            frame.lines.forEachIndexed { index, line ->
                val y = index * metrics.lineHeight
                if (y + metrics.lineHeight < top || y > bottom) return@forEachIndexed
                line.forEach { run ->
                    drawRun(run, y, metrics, layouts, charLayouts, measurer, style, tokens.terminal)
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            frame.lines.forEach { line ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(lineHeightDp)
                        .semantics { text = AnnotatedString(line.joinToString("") { it.text }) },
                )
            }
        }
    }
}

/**
 * Draws one run at its place on a line: its background where it has one, then its glyphs. The cursor is a block in the text colour,
 * and an inverse run swaps its colours.
 */
private fun DrawScope.drawRun(
    run: CellRun,
    y: Float,
    metrics: CellMetrics,
    layouts: Map<CellRun, TextLayoutResult>,
    charLayouts: MutableMap<Pair<Char, CellStyle>, TextLayoutResult>,
    measurer: TextMeasurer,
    style: TextStyle,
    terminal: Color,
) {
    val colours = runColours(run, terminal)
    val left = run.col * metrics.cellWidth
    val width = run.cells * metrics.cellWidth
    colours.background?.let { drawRect(it, Offset(left, y), Size(width, metrics.lineHeight)) }
    val layout = layouts[run] ?: return
    if (run.text.none { TerminalGlyphs.glyphFor(it) != null }) {
        drawText(layout, color = colours.foreground, topLeft = Offset(left, y))
        return
    }
    // A run that holds block or box glyphs is drawn cell by cell: the geometry as shapes, every character from its own
    // layout inside its own cell. Drawing from the run's layout would offset each character that follows a geometric
    // glyph by that glyph's fallback advance, which is exactly the misalignment this fixes (0.3.1, opencode's banner).
    if (charLayouts.size > LAYOUT_CACHE_LIMIT) charLayouts.clear()
    run.text.forEachIndexed { index, ch ->
        val cellLeft = left + index * metrics.cellWidth
        val glyph = TerminalGlyphs.glyphFor(ch)
        if (glyph != null) {
            drawGlyph(glyph, ch, cellLeft, y, metrics, colours.foreground)
        } else {
            val charLayout = charLayouts.getOrPut(ch to run.style) {
                measurer.measure(AnnotatedString(ch.toString()), runStyle(run.style, style), softWrap = false)
            }
            clipRect(cellLeft, y, cellLeft + metrics.cellWidth, y + metrics.lineHeight) {
                drawText(charLayout, color = colours.foreground, topLeft = Offset(cellLeft, y))
            }
        }
    }
}

/** Draws one geometric glyph, in cell fractions, inside its cell. Shade blocks keep their alpha. */
private fun DrawScope.drawGlyph(
    glyph: TerminalGlyphs.Glyph,
    ch: Char,
    left: Float,
    top: Float,
    metrics: CellMetrics,
    colour: Color,
) {
    val w = metrics.cellWidth
    val h = metrics.lineHeight
    val shade = TerminalGlyphs.shadeAlphaFor(ch)
    val ink = if (shade == null) colour else colour.copy(alpha = colour.alpha * shade)
    glyph.filled.forEach { part ->
        drawRect(
            ink,
            Offset(left + part.left * w, top + part.top * h),
            Size((part.right - part.left) * w, (part.bottom - part.top) * h),
        )
    }
    val lightThickness = max(1f, w * 0.08f)
    glyph.strokes.forEach { line ->
        // A heavy line is drawn thicker than a light one, so a heavy frame still reads as heavy.
        val thickness = if (line.heavy) lightThickness * HEAVY_STROKE_FACTOR else lightThickness
        val shiftX = if (line.double && line.fromX == line.toX) w * 0.18f else 0f
        val shiftY = if (line.double && line.fromY == line.toY) w * 0.18f else 0f
        val shift = if (line.double) listOf(-1f, 1f) else listOf(1f)
        shift.forEach { side ->
            drawLine(
                ink,
                Offset(left + line.fromX * w - side * shiftX, top + line.fromY * h - side * shiftY),
                Offset(left + line.toX * w - side * shiftX, top + line.toY * h - side * shiftY),
                strokeWidth = thickness,
            )
        }
    }
}

/** The colours a run is drawn in: its foreground, and its background when it has one. */
private class RunColours(val foreground: Color, val background: Color?)

/**
 * The colours of a run. A default colour is the theme's: the text colour for the foreground, and no background. Inverse swaps the two
 * (a default takes the other default); the cursor is drawn as a block in the text colour, with the terminal colour on it.
 */
private fun runColours(run: CellRun, terminal: Color): RunColours {
    val style = run.style
    val foregroundOwn = style.fg.colourOrNull()
    val backgroundOwn = style.bg.colourOrNull()
    var foreground = foregroundOwn ?: WhistlTerminalText
    var background = backgroundOwn
    if (style.inverse) {
        foreground = backgroundOwn ?: terminal
        background = foregroundOwn ?: WhistlTerminalText
    }
    if (run.cursor) {
        foreground = terminal
        background = WhistlTerminalText
    }
    if (style.dim) foreground = foreground.copy(alpha = DIM_ALPHA)
    return RunColours(foreground, background)
}

/** The text style of one run: the monospace style with its weight, slant and decorations. */
private fun runStyle(style: CellStyle, base: TextStyle): TextStyle = base.merge(
    TextStyle(
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = decorationOf(style),
    ),
)

/** The text decoration of a style: underline, strike-through, both, or none. */
private fun decorationOf(style: CellStyle): TextDecoration? = when {
    style.underline && style.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    style.underline -> TextDecoration.Underline
    style.strike -> TextDecoration.LineThrough
    else -> null
}

/** The colour of a cell colour, or `null` for the terminal's default, which the caller supplies. */
private fun CellColor.colourOrNull(): Color? = when (this) {
    CellColor.Default -> null
    is CellColor.Rgb -> Color(0xFF000000L or value.toLong())
}

/** The one-line status bar, on the accent colour: `[machine] space · agent` and the status. */
@Composable
private fun StatusBar(state: AgentUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.agent_bar_place,
                state.machineName,
                state.spaceName ?: stringResource(R.string.common_no_space),
                state.name,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(statusLabelRes(state.statusLabel)),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/** The shortcut keys in the order Settings holds them. Ctrl shows armed; a disabled bar does nothing when tapped. */
@Composable
private fun KeyBar(state: AgentUiState, onKey: (ShortcutKey) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalWhistlTokens.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var index = 0
        while (index < state.keyBar.size) {
            val key = state.keyBar[index]
            if (ShortcutBar.isArrow(key)) {
                // The bundle is one cluster: the four arrows share a container instead of four separate buttons.
                ArrowCluster(enabled = state.canType, onKey = onKey)
                while (index < state.keyBar.size && ShortcutBar.isArrow(state.keyBar[index])) index++
                continue
            }
            val armed = key == ShortcutKey.CTRL && state.ctrlArmed
            val description = if (armed) {
                stringResource(R.string.agent_ctrl_armed_description)
            } else {
                stringResource(keyNameRes(key))
            }
            Button(
                onClick = { onKey(key) },
                enabled = state.canType,
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = whistlButtonColors(
                    containerColor = if (armed) MaterialTheme.colorScheme.primary else tokens.field,
                    contentColor = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .widthIn(min = MinTouchTarget)
                    .semantics { contentDescription = description },
            ) {
                Text(text = key.label, fontFamily = WhistlMono, style = MaterialTheme.typography.labelLarge)
            }
            index++
        }
    }
}

/**
 * The arrow bundle in one compact cluster: four cells sharing a container, no gaps and no per-key chrome, so the bar keeps
 * room for the other keys. Each cell is still a 48 dp touch target and still names its key; the bundle is enabled or
 * disabled as a whole (Settings holds it as one entry).
 *
 * @param enabled whether the terminal takes input.
 * @param onKey called with the arrow that was tapped.
 */
@Composable
private fun ArrowCluster(enabled: Boolean, onKey: (ShortcutKey) -> Unit) {
    val tokens = LocalWhistlTokens.current
    val ink = if (enabled) MaterialTheme.colorScheme.onSurface else tokens.muted
    // One control, always there: the four arrows are segments of a single rounded container, separated by hairlines
    // instead of gaps. It never collapses — sending an arrow must stay one tap, and stepping up three lines must not
    // mean opening the bundle three times (0.3.4). Each segment is a 48 dp target, which is what keeps it this wide;
    // narrower would either break the touch target or need a popover, and a popover is what made it impractical.
    Row(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .background(tokens.field, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutBar.ARROWS.forEachIndexed { index, arrow ->
            val description = stringResource(keyNameRes(arrow))
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .height(18.dp)
                        .background(tokens.muted.copy(alpha = 0.30f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = MinTouchTarget, height = MinTouchTarget)
                    .clickable(enabled = enabled) { onKey(arrow) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = arrow.label,
                    fontFamily = WhistlMono,
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                )
            }
        }
    }
}

/** What the screen is doing: the Ctrl hint, the last key sent, or nothing. */
@Composable
private fun HintLine(state: AgentUiState) {
    val lastKey = state.lastKey
    val text = when {
        state.ctrlArmed -> stringResource(R.string.agent_ctrl_on)
        lastKey != null -> stringResource(R.string.agent_key_sent, sentKeyText(lastKey))
        else -> null
    }
    if (text != null) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = LocalWhistlTokens.current.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * The key as the hint shows it: its name, with "Ctrl+" in front when it was sent with Ctrl.
 *
 * @param key the last key sent.
 * @return the text the hint line shows for the key.
 */
@Composable
private fun sentKeyText(key: SentKey): String =
    if (key.ctrl) stringResource(R.string.agent_key_ctrl, key.label) else key.label

/**
 * The notice line: why input is off, or the last refusal (red), or, for a blocked agent, what Send does. Nothing shows when the
 * terminal is ready.
 */
@Composable
private fun NoticeLine(state: AgentUiState) {
    val notice = state.notice ?: return
    val text = when (notice) {
        AgentNotice.Offline -> stringResource(R.string.agent_notice_offline, state.machineName)
        AgentNotice.Gone -> stringResource(R.string.agent_notice_gone)
        AgentNotice.Finished -> stringResource(R.string.agent_notice_finished)
        AgentNotice.Blocked -> stringResource(R.string.agent_notice_blocked)
        AgentNotice.Refused -> stringResource(R.string.agent_notice_refused)
    }
    val color = if (notice == AgentNotice.Blocked || notice == AgentNotice.Refused) {
        MaterialTheme.colorScheme.error
    } else {
        LocalWhistlTokens.current.muted
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 4.dp))
}

/** For a blocked agent, while nothing else is noticed: what Send does (it goes in forced). */
@Composable
private fun BlockedHint(state: AgentUiState) {
    if (state.notice == null && state.blockedAgent) {
        Text(
            text = stringResource(R.string.agent_hint_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = LocalWhistlTokens.current.blocked,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * The row a thumb uses: show or hide the keyboard, paste, then the shortcut keys (phase 9, SPEC 2.5).
 *
 * There is still no text field: the 1 dp transparent box below exists only to receive the IME, and the terminal draws
 * the line. The keyboard is what the screen is for, so the first icon is its toggle and the second sends the phone's
 * clipboard at the cursor.
 *
 * @param state the screen's state, for whether input is on.
 * @param onKey called with a shortcut key from the bar.
 * @param onType called with the characters inserted.
 * @param onBackspace called with how many characters were deleted.
 * @param onPaste called with the clipboard's text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    state: AgentUiState,
    onKey: (ShortcutKey) -> Unit,
    onType: (String) -> Unit,
    onBackspace: (Int) -> Unit,
    onPaste: (String) -> Unit,
    keyboardRequest: Int = 0,
) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.isImeVisible
    val shadow = remember { mutableStateOf("") }

    fun openKeyboard() {
        scope.launch {
            runCatching { focus.requestFocus() }
            // The IME is asked for after the focus has been applied: asking in the same frame is a race the device loses.
            withFrameNanos { }
            keyboard?.show()
        }
    }
    // The keyboard is the point of the screen: it opens with it, once the terminal can take input.
    LaunchedEffect(state.canType) { if (state.canType) openKeyboard() }
    LaunchedEffect(keyboardRequest) { if (keyboardRequest > 0) openKeyboard() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (imeVisible) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                } else {
                    openKeyboard()
                }
            },
            enabled = state.canType,
            modifier = Modifier.size(MinTouchTarget).testTag(AGENT_KEYBOARD_TOGGLE_TAG),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard),
                contentDescription = stringResource(
                    if (imeVisible) R.string.agent_keyboard_hide else R.string.agent_keyboard_show,
                ),
                tint = if (state.canSend) MaterialTheme.colorScheme.onSurface else LocalWhistlTokens.current.muted,
            )
        }
        IconButton(
            onClick = { clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(onPaste) },
            enabled = state.canType,
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_paste),
                contentDescription = stringResource(R.string.agent_paste),
                tint = if (state.canSend) MaterialTheme.colorScheme.onSurface else LocalWhistlTokens.current.muted,
            )
        }
        BasicTextField(
            value = shadow.value,
            onValueChange = { next ->
                val prev = shadow.value
                val common = prev.commonPrefixWith(next).length
                val removed = prev.length - common
                if (removed > 0) onBackspace(removed)
                val added = next.substring(common)
                if (added.isNotEmpty()) onType(added.replace("\n", "\r"))
                // Enter starts a new line on the terminal: the mirror forgets the finished one.
                shadow.value = if (added.contains('\n')) "" else next.takeLast(SHADOW_LIMIT)
            },
            enabled = state.canType,
            textStyle = LocalTextStyle.current.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.None,
                keyboardType = KeyboardType.Text,
            ),
            modifier = Modifier
                .requiredSize(1.dp)
                .alpha(0f)
                .focusRequester(focus)
                // A keyboard sink is not a control: no name, no size, nothing for a screen reader to land on.
                .clearAndSetSemantics { }
                .testTag(AGENT_KEYBOARD_TAG),
        )
        KeyBar(state = state, onKey = onKey, modifier = Modifier.weight(1f))
    }
}

/** The test tag of the keyboard toggle. */
const val AGENT_KEYBOARD_TOGGLE_TAG: String = "agent-keyboard-toggle"


/** How many characters the keyboard's mirror keeps; the terminal owns the line, this only recognises deletions. */
private const val SHADOW_LIMIT: Int = 256

/** The test tag of the invisible field that receives the keyboard. */
const val AGENT_KEYBOARD_TAG: String = "agent-keyboard"

/** One row of the overflow menu sheet; a danger row is red. Touch target: the whole row, at least 48 dp. */
@Composable
private fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The terminal's font size in sp (SPEC §2.6): small 11, medium 12.5, large 14.5, as the prototype sets them. */
fun fontSizeFor(size: FontSize): TextUnit = when (size) {
    FontSize.Small -> 11.sp
    FontSize.Medium -> 12.5.sp
    FontSize.Large -> 14.5.sp
}

/** The label resource for a status-bar label. */
private fun statusLabelRes(label: AgentStatusLabel): Int = when (label) {
    AgentStatusLabel.Working -> R.string.status_working
    AgentStatusLabel.NeedsYou -> R.string.status_needs_you
    AgentStatusLabel.Done -> R.string.status_done
    AgentStatusLabel.Idle -> R.string.status_idle
    AgentStatusLabel.Error -> R.string.status_error
    AgentStatusLabel.Plain -> R.string.status_plain
    AgentStatusLabel.Finished -> R.string.status_finished
    AgentStatusLabel.Offline -> R.string.machine_status_offline
}
