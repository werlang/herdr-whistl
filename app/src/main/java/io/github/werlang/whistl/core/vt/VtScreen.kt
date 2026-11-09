package io.github.werlang.whistl.core.vt

import io.github.werlang.whistl.domain.CellColor
import io.github.werlang.whistl.domain.CellRun
import io.github.werlang.whistl.domain.CellStyle
import io.github.werlang.whistl.domain.TerminalFrame
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The screen behind the Agent screen (SPEC §2.5, §5.1): the bytes of a terminal's channel go in with [write], and what it holds comes
 * out as a [TerminalFrame]. One instance per open terminal. It is protocol-free and does not touch Android.
 *
 * The engine ([VtEngine]) does the terminal work; this class turns its grid into the runs the screen draws: the palette is resolved
 * ([Xterm256]), a blank tail of a line is left out, the cursor's cell is a run of its own, and a history line is converted once.
 *
 * Every call takes the instance's lock, so a [frame] is always taken between two writes and never shows half of one.
 *
 * @param cols the initial width in cells.
 * @param rows the initial height in cells.
 * @param engine the terminal engine.
 */
class VtScreen(cols: Int, rows: Int, private val engine: VtEngine) {
    /** The width in cells. */
    @Volatile
    var cols: Int = cols
        private set

    /** The height in cells. */
    @Volatile
    var rows: Int = rows
        private set

    /** The runs of each history line already converted, by the line's identity. */
    private val lineRuns = IdentityHashMap<VtRow, List<CellRun>>()

    /**
     * Feeds the program's bytes to the engine, in order.
     *
     * @param bytes the bytes of one DATA frame.
     */
    @Synchronized
    fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) engine.write(bytes)
    }

    /**
     * Changes the size. The program redraws itself after the core's own resize; this only keeps the grid in step.
     *
     * @param cols the new width in cells.
     * @param rows the new height in cells.
     * @throws IllegalArgumentException when a size is not at least one cell.
     */
    @Synchronized
    fun resize(cols: Int, rows: Int) {
        require(cols > 0 && rows > 0) { "a terminal has at least one cell" }
        engine.resize(cols, rows)
        this.cols = cols
        this.rows = rows
        lineRuns.clear()
    }

    /**
     * The screen as it is now: up to [historyLines] lines of history, then the screen rows, with the cursor.
     *
     * @param historyLines the most history lines to include, the newest kept.
     * @return the frame.
     */
    @Synchronized
    fun frame(historyLines: Int = SHOWN_HISTORY_LINES): TerminalFrame {
        val snapshot = engine.snapshot(historyLines)
        val history = snapshot.history
        val lines = ArrayList<List<CellRun>>(history.size + snapshot.rows.size)
        for (row in history) lines += lineRuns.getOrPut(row) { runs(row, cursorCol = -1) }
        val keep: MutableSet<VtRow> = Collections.newSetFromMap(IdentityHashMap())
        keep.addAll(history)
        lineRuns.keys.retainAll(keep)

        val screenStart = lines.size
        snapshot.rows.forEachIndexed { index, row ->
            val cursor = if (snapshot.cursorVisible && index == snapshot.cursorRow) snapshot.cursorCol else -1
            lines += runs(row, cursorCol = cursor)
        }
        val cursorOnScreen = snapshot.cursorVisible && snapshot.cursorRow in snapshot.rows.indices
        return TerminalFrame(
            lines = lines,
            screenStart = screenStart,
            cursorLine = if (cursorOnScreen) screenStart + snapshot.cursorRow else -1,
            cols = cols,
            rows = rows,
        )
    }

    /**
     * The runs of one line, each at its column. Trailing blank cells are left out, except that the cursor's cell always is. Cells with
     * the same attributes share a run; the cursor's cell and a wide character are runs of their own. A wide character's second cell
     * belongs to its run and is not drawn again.
     */
    private fun runs(row: VtRow, cursorCol: Int): List<CellRun> {
        // A cursor past the last cell of a line still has a cell to be drawn in: the line is padded with blanks up to it.
        val cells = if (cursorCol >= row.cells.size) row.cells + List(cursorCol - row.cells.size + 1) { VtCell("") } else row.cells
        var end = cells.size
        while (end > 0 && isBlank(cells[end - 1]) && end - 1 != cursorCol) end--
        if (cursorCol in 0 until cells.size) end = maxOf(end, cursorCol + 1)
        val runs = ArrayList<CellRun>()
        var text = StringBuilder()
        var style: CellStyle? = null
        var runStart = 0
        fun flush(upTo: Int, cursor: Boolean) {
            val current = style
            if (text.isNotEmpty() && current != null) {
                runs += CellRun(text.toString(), current, cursor, col = runStart, cells = upTo - runStart)
            }
            text = StringBuilder()
            style = null
            runStart = upTo
        }
        var index = 0
        while (index < end) {
            val cell = cells[index]
            if (cell.wideSpacer) {
                index++
                continue
            }
            val cellStyle = styleOf(cell)
            val isCursor = index == cursorCol
            if (style != cellStyle || cell.wide || isCursor) flush(index, cursor = false)
            style = cellStyle
            text.append(glyph(cell))
            val after = if (cell.wide) index + 2 else index + 1
            if (cell.wide || isCursor) flush(after, cursor = isCursor)
            index = after
        }
        flush(end, cursor = false)
        return runs
    }

    private fun styleOf(cell: VtCell): CellStyle = CellStyle(
        fg = colour(cell.fg),
        bg = colour(cell.bg),
        bold = cell.bold,
        italic = cell.italic,
        dim = cell.dim,
        underline = cell.underline,
        strike = cell.strike,
        inverse = cell.inverse,
    )

    /** The glyph of a cell. A concealed cell draws as a space, so its text is not shown. */
    private fun glyph(cell: VtCell): String = if (cell.conceal) " " else cell.glyph.ifEmpty { " " }

    private fun isBlank(cell: VtCell): Boolean =
        !cell.wideSpacer && (cell.glyph.isEmpty() || cell.glyph == " ") && styleOf(cell) == BLANK_STYLE

    /** A palette colour becomes its RGB, and the default stays the default (the theme's). */
    private fun colour(colour: VtColour): CellColor = when (colour) {
        VtColour.Default -> CellColor.Default
        is VtColour.Indexed -> CellColor.Rgb(Xterm256.rgb(colour.index))
        is VtColour.Rgb -> CellColor.Rgb(colour.value)
    }

    companion object {
        /** The most history lines the engine keeps, by default. */
        const val MAX_SCROLLBACK: Int = 2_000

        /** The most history lines a frame carries: the screen shows the newest of them. */
        const val SHOWN_HISTORY_LINES: Int = 1_000

        private val BLANK_STYLE = CellStyle()
    }
}
