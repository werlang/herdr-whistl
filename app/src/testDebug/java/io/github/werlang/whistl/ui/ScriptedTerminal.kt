package io.github.werlang.whistl.ui

import io.github.werlang.whistl.core.vt.VtCell
import io.github.werlang.whistl.core.vt.VtColour
import io.github.werlang.whistl.core.vt.VtEngine
import io.github.werlang.whistl.core.vt.VtRow
import io.github.werlang.whistl.core.vt.VtScreen
import io.github.werlang.whistl.core.vt.VtSnapshot
import io.github.werlang.whistl.domain.TerminalFrame

/**
 * A [VtEngine] whose grid is written by the test, not by a program: the screenshot tests draw a known screen through the same
 * [VtScreen] mapping the device uses (SPEC §2.5). Bytes and resizes are not interpreted.
 *
 * @param grid the grid to hold.
 */
class ScriptedEngine(private val grid: VtSnapshot) : VtEngine {
    override fun write(bytes: ByteArray) = Unit

    override fun resize(cols: Int, rows: Int) = Unit

    override fun snapshot(maxHistory: Int): VtSnapshot = grid
}

/** A run of text with its attributes, for building a scripted row. */
private class Seg(
    val text: String,
    val fg: VtColour = VtColour.Default,
    val bg: VtColour = VtColour.Default,
    val bold: Boolean = false,
    val inverse: Boolean = false,
)

/** One row of [cols] cells from its segments, padded with blank cells. */
private fun row(cols: Int, vararg segs: Seg): List<VtCell> {
    val cells = ArrayList<VtCell>()
    for (seg in segs) {
        for (ch in seg.text) {
            cells += VtCell(ch.toString(), fg = seg.fg, bg = seg.bg, bold = seg.bold, inverse = seg.inverse)
        }
    }
    while (cells.size < cols) cells += VtCell("")
    return cells.take(cols)
}

/** A screen of [rows] scripted rows, drawn through [VtScreen] with the cursor at ([cursorRow], [cursorCol]). */
private fun screenOf(cols: Int, grid: List<List<VtCell>>, cursorRow: Int, cursorCol: Int, cursorVisible: Boolean): TerminalFrame {
    val snapshot = VtSnapshot(
        history = emptyList<VtRow>(),
        rows = grid.map { VtRow(it) },
        cursorRow = cursorRow,
        cursorCol = cursorCol,
        cursorVisible = cursorVisible,
    )
    return VtScreen(cols, grid.size, ScriptedEngine(snapshot)).frame()
}

/**
 * The Agent screen with colour: the first paint of `attach_frames.bin` (red, bold green, 24-bit orange, a magenta bold line), then
 * a row of palette colours and a row of 24-bit colours, as `attach_frames_256.bin` draws them. The cursor is on the prompt.
 */
fun colourScreen(): TerminalFrame {
    val cols = 40
    val red = VtColour.Indexed(1)
    val green = VtColour.Indexed(2)
    val magenta = VtColour.Indexed(5)
    val grid = listOf(
        row(cols, Seg("red", fg = red), Seg(" "), Seg("bold green", fg = green, bold = true)),
        row(cols, Seg("true", fg = VtColour.Rgb(0xFF6400))),
        row(cols, Seg("hi")),
        row(cols, Seg("hi", fg = magenta, bold = true)),
        row(
            cols,
            Seg("  ", bg = VtColour.Indexed(16)), Seg("  ", bg = VtColour.Indexed(21)), Seg("  ", bg = VtColour.Indexed(46)),
            Seg("  ", bg = VtColour.Indexed(51)), Seg("  ", bg = VtColour.Indexed(196)), Seg("  ", bg = VtColour.Indexed(202)),
            Seg("  ", bg = VtColour.Indexed(226)), Seg("  ", bg = VtColour.Indexed(231)), Seg("  ", bg = VtColour.Indexed(232)),
            Seg("  ", bg = VtColour.Indexed(240)), Seg("  ", bg = VtColour.Indexed(250)), Seg("  ", bg = VtColour.Indexed(255)),
        ),
        row(
            cols,
            *(0 until 16).map { step -> Seg(" ", bg = VtColour.Rgb((step * 16) shl 16 or (0x40 shl 8) or (0xC0 - step * 8))) }.toTypedArray(),
        ),
        row(cols),
        row(cols, Seg("# ")),
    )
    return screenOf(cols, grid, cursorRow = 7, cursorCol = 2, cursorVisible = true)
}

/**
 * The Agent screen on the alternate screen, drawn as htop draws it: three meter rows, a header bar in inverse green, the process
 * rows with coloured columns (one selected), and the function-key bar in inverse. Nothing of the main screen is in it.
 */
fun htopScreen(): TerminalFrame {
    val cols = 56
    val green = VtColour.Indexed(2)
    val yellow = VtColour.Indexed(3)
    val red = VtColour.Indexed(1)
    val cyan = VtColour.Indexed(6)
    val magenta = VtColour.Indexed(5)
    val black = VtColour.Indexed(0)
    val grey = VtColour.Indexed(8)
    val grid = listOf(
        row(cols, Seg("0["), Seg("|||", fg = green), Seg("|", fg = yellow), Seg("      4.5%]"), Seg("   Tasks: "), Seg("147", fg = cyan, bold = true)),
        row(cols, Seg("1["), Seg("|||", fg = green), Seg("           3.3%]"), Seg("   Load: "), Seg("0.43", bold = true)),
        row(cols, Seg("Mem["), Seg("||||||||", fg = green), Seg("|", fg = red), Seg("   3.47G/7.75G]"), Seg("   Swp[", fg = grey)),
        row(cols),
        row(cols, Seg("  PID USER      PRI  NI   VIRT   RES  CPU%", fg = black, bg = green, bold = true)),
        row(cols, Seg("3231303 root       20   0  9400  7872   2.0", inverse = true)),
        row(cols, Seg("   2021 root       20   0  3761M  151M   0.7", fg = magenta)),
        row(cols, Seg("   2912 root       20   0  31.1G  179M   0.7")),
        row(cols, Seg("   3755 999        20   0  2347M  79428  0.7", fg = cyan)),
        row(cols, Seg("      1 root       20   0 26828  15928  0.0", fg = grey)),
        row(cols),
        row(cols, Seg("F1", inverse = true), Seg("Help  "), Seg("F2", inverse = true), Seg("Setup "), Seg("F3", inverse = true), Seg("Search "), Seg("F10", inverse = true), Seg("Quit")),
    )
    return screenOf(cols, grid, cursorRow = 5, cursorCol = 0, cursorVisible = false)
}
