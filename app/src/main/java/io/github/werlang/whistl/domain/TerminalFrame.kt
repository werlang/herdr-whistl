package io.github.werlang.whistl.domain

/**
 * The colour of one cell: the terminal's own default, or a 24-bit colour. Palette colours (16 and 256) are resolved to RGB
 * before they reach the domain, so the screen only has to draw two kinds of colour.
 */
sealed interface CellColor {
    /** The terminal's default foreground or background; the screen takes it from its theme. */
    data object Default : CellColor

    /**
     * A 24-bit colour.
     *
     * @property value the colour as 0xRRGGBB.
     */
    data class Rgb(val value: Int) : CellColor
}

/**
 * The attributes shared by a run of cells (SPEC §2.5). [inverse] swaps the foreground and background when drawn.
 */
data class CellStyle(
    val fg: CellColor = CellColor.Default,
    val bg: CellColor = CellColor.Default,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val inverse: Boolean = false,
)

/**
 * Consecutive cells of one line that share a style, as their text, at their place on the line. A wide character is a run of its own
 * (two cells). [cursor] marks the cell under the cursor, which is always a run of its own.
 *
 * @property text the characters of the cells.
 * @property style the attributes they share.
 * @property cursor `true` for the run that holds the cursor.
 * @property col the column of the run's first cell.
 * @property cells how many columns the run takes.
 */
data class CellRun(
    val text: String,
    val style: CellStyle = CellStyle(),
    val cursor: Boolean = false,
    val col: Int = 0,
    val cells: Int = text.length,
)

/**
 * The part of a terminal the Agent screen shows (SPEC §2.5): the history kept, then the screen rows. A frame is immutable and
 * is one consistent state of the emulator, so the screen never draws half an update.
 *
 * @property lines the lines to show, oldest first: the history, then the screen rows.
 * @property screenStart the index in [lines] of the first screen row.
 * @property cursorLine the index in [lines] of the cursor's row, or `-1` when the cursor is hidden.
 * @property cols the screen width in cells.
 * @property rows the screen height in cells.
 */
class TerminalFrame(
    val lines: List<List<CellRun>>,
    val screenStart: Int,
    val cursorLine: Int,
    val cols: Int,
    val rows: Int,
) {
    /** The frame's text, lines joined with `\n`, without any style. For tests and for the text of a plain frame. */
    fun plainText(): String = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    override fun equals(other: Any?): Boolean =
        other is TerminalFrame &&
            other.lines == lines &&
            other.screenStart == screenStart &&
            other.cursorLine == cursorLine &&
            other.cols == cols &&
            other.rows == rows

    override fun hashCode(): Int = lines.hashCode() * 31 + screenStart

    /** Shows the frame's size and line count, never its text: terminal content is data and stays out of logs. */
    override fun toString(): String = "TerminalFrame(cols=$cols, rows=$rows, lines=${lines.size})"

    companion object {
        /** An empty screen of [cols] by [rows]: nothing has been written yet. */
        fun empty(cols: Int, rows: Int): TerminalFrame = TerminalFrame(
            lines = List(rows) { emptyList() },
            screenStart = 0,
            cursorLine = -1,
            cols = cols,
            rows = rows,
        )

        /**
         * A frame of plain text, one run per line, with no history and the cursor hidden. Used by the demo core and the tests.
         *
         * @param text the lines, separated by `\n`.
         * @param cols the width in cells; the frame does not wrap the text.
         */
        fun plain(text: String, cols: Int = 80): TerminalFrame {
            val lines = if (text.isEmpty()) emptyList() else text.split('\n').map { line -> listOf(CellRun(line, cells = line.length)) }
            return TerminalFrame(lines = lines, screenStart = 0, cursorLine = -1, cols = cols, rows = lines.size)
        }
    }
}
