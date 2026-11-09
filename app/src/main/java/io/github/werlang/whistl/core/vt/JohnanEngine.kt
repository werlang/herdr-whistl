package io.github.werlang.whistl.core.vt

import com.johnan.terminal.core.Osc52Policy
import com.johnan.terminal.core.TerminalCell
import com.johnan.terminal.core.TerminalColor
import com.johnan.terminal.core.TerminalConfig
import com.johnan.terminal.core.TerminalEmulator
import com.johnan.terminal.core.UnderlineStyle
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The [VtEngine] of the Agent screen: kmp-terminal-emulator's `terminal-core` (phase 8 P1 decision, SPEC §2.5). This is the only file
 * that imports the library. The library's classes are compiled for Java 21, so the device build loads them and the JVM unit tests do
 * not; the engine's own behaviour is checked by the instrumented tests in `app/src/androidTest`.
 *
 * The emulator answers the program's own queries and drops those answers here: the core is the one the program talks to. Clipboard
 * writes (OSC 52) are denied, and the title is ignored. Kitty graphics are off.
 *
 * @param cols the initial width in cells.
 * @param rows the initial height in cells.
 * @param maxScrollback how many history lines the emulator keeps.
 */
class JohnanEngine(cols: Int, rows: Int, maxScrollback: Int) : VtEngine {
    private val emulator = TerminalEmulator(
        TerminalConfig(
            initialRows = rows,
            initialCols = cols,
            maxScrollback = maxScrollback,
            osc52Policy = Osc52Policy.ALWAYS_DENY,
            kittyGraphicsEnabled = false,
        ),
        { _, _ -> },
        { },
        { },
    )

    /** The history lines already converted, by the emulator's own row object: a row that has scrolled up is not converted again. */
    private val converted = IdentityHashMap<Array<TerminalCell>, VtRow>()

    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) emulator.processOutputSync(bytes, bytes.size)
    }

    override fun resize(cols: Int, rows: Int) {
        // The library takes the rows first (its ScreenBuffer.resize(rows, cols)); the device test caught the swapped order.
        emulator.resizeSync(rows, cols)
        converted.clear()
    }

    override fun snapshot(maxHistory: Int): VtSnapshot {
        val state = emulator.snapshotScreenState()
        val scrollback = state.scrollback
        val shown = scrollback.subList((scrollback.size - maxHistory).coerceAtLeast(0), scrollback.size)
        val history = shown.map { cells -> converted.getOrPut(cells) { VtRow(cells.map(::cell)) } }
        // Forget the rows that have left the window, so the cache holds no more than the history shown.
        val live: MutableSet<Array<TerminalCell>> = Collections.newSetFromMap(IdentityHashMap())
        live.addAll(shown)
        converted.keys.retainAll(live)
        return VtSnapshot(
            history = history,
            rows = state.rows.map { cells -> VtRow(cells.map(::cell)) },
            cursorRow = state.cursorRow,
            cursorCol = state.cursorCol,
            cursorVisible = state.cursorVisible,
        )
    }

    private fun cell(cell: TerminalCell): VtCell = VtCell(
        glyph = if (cell.isWideSpacer) "" else cell.glyphString(),
        wide = !cell.isWideSpacer && cell.width == 2,
        wideSpacer = cell.isWideSpacer,
        fg = colour(cell.foregroundColor),
        bg = colour(cell.backgroundColor),
        bold = cell.bold,
        italic = cell.italic,
        dim = cell.dim,
        underline = cell.underline || cell.underlineStyle != UnderlineStyle.NONE,
        strike = cell.strikethrough,
        inverse = cell.reverse,
        conceal = cell.conceal,
    )

    /** A colour as the engine holds it: the palette index for a standard or 256-colour cell, the RGB for a 24-bit one. */
    private fun colour(color: TerminalColor): VtColour = when (color) {
        TerminalColor.Default -> VtColour.Default
        is TerminalColor.Standard -> VtColour.Indexed(color.code)
        is TerminalColor.Palette256 -> VtColour.Indexed(color.code)
        is TerminalColor.Rgb -> VtColour.Rgb((color.r shl 16) or (color.g shl 8) or color.b)
        else -> VtColour.Default
    }
}
