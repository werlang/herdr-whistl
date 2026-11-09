package io.github.werlang.whistl.core.vt

import io.github.werlang.whistl.domain.CellColor
import io.github.werlang.whistl.domain.CellRun
import io.github.werlang.whistl.domain.CellStyle
import io.github.werlang.whistl.domain.TerminalFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screen draws from the engine's grid (SPEC §2.5): the palette, the attributes, the runs, the cursor, the history. The engine is
 * scripted, so these run on the JVM; the engine's own escape-sequence handling is checked on the device (`app/src/androidTest`).
 */
class VtScreenMappingTest {

    /** An engine that returns the snapshot it is given, and records what the screen asked of it. */
    private class ScriptedEngine(var snapshot: VtSnapshot) : VtEngine {
        val writes = mutableListOf<ByteArray>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        var lastMaxHistory = -1

        override fun write(bytes: ByteArray) {
            writes += bytes
        }

        override fun resize(cols: Int, rows: Int) {
            resizes += cols to rows
        }

        override fun snapshot(maxHistory: Int): VtSnapshot {
            lastMaxHistory = maxHistory
            return snapshot
        }
    }

    private fun cells(text: String, style: (VtCell) -> VtCell = { it }): List<VtCell> =
        text.map { style(VtCell(it.toString())) }

    private fun snapshot(
        rows: List<List<VtCell>>,
        history: List<List<VtCell>> = emptyList(),
        cursorRow: Int = 0,
        cursorCol: Int = 0,
        cursorVisible: Boolean = false,
    ) = VtSnapshot(
        history = history.map { VtRow(it) },
        rows = rows.map { VtRow(it) },
        cursorRow = cursorRow,
        cursorCol = cursorCol,
        cursorVisible = cursorVisible,
    )

    private fun screenOf(snapshot: VtSnapshot, cols: Int = 20, rows: Int = snapshot.rows.size): VtScreen =
        VtScreen(cols, rows, ScriptedEngine(snapshot))

    /** The palette indexes resolve through the xterm table: the standard colours, the cube, and the greys. */
    @Test
    fun paletteIndexesResolveThroughXterm() {
        val frame = screenOf(
            snapshot(
                rows = listOf(
                    listOf(
                        VtCell("R", fg = VtColour.Indexed(1)),
                        VtCell("C", fg = VtColour.Indexed(196)),
                        VtCell("G", fg = VtColour.Indexed(10)),
                        VtCell("H", fg = VtColour.Indexed(244)),
                    ),
                ),
            ),
        ).frame()

        val styles = frame.screenRow(0).associate { it.text to it.style.fg }
        assertEquals(CellColor.Rgb(0xCD0000), styles.getValue("R"))
        assertEquals(CellColor.Rgb(0xFF0000), styles.getValue("C"))
        assertEquals(CellColor.Rgb(0x00FF00), styles.getValue("G"))
        assertEquals(CellColor.Rgb(0x808080), styles.getValue("H"))
    }

    /** The xterm palette itself: its ends and its boundaries. */
    @Test
    fun xtermPaletteHasTheStandardValues() {
        assertEquals(0x000000, Xterm256.rgb(0))
        assertEquals(0x00FF00, Xterm256.rgb(10))
        assertEquals(0x000000, Xterm256.rgb(16))
        assertEquals(0x0000FF, Xterm256.rgb(21))
        assertEquals(0xFFFFFF, Xterm256.rgb(231))
        assertEquals(0x080808, Xterm256.rgb(232))
        assertEquals(0xEEEEEE, Xterm256.rgb(255))
    }

    /** A palette index outside 0 to 255 is refused rather than drawn wrongly. */
    @Test(expected = IllegalArgumentException::class)
    fun aPaletteIndexOutOfRangeIsRefused() {
        Xterm256.rgb(256)
    }

    /** A 24-bit colour is drawn exactly; the default stays the default, for the theme to supply. */
    @Test
    fun truecolourIsExactAndTheDefaultStaysTheDefault() {
        val frame = screenOf(
            snapshot(rows = listOf(listOf(VtCell("T", fg = VtColour.Rgb(0xFF6400), bg = VtColour.Rgb(0x010203)), VtCell("D")))),
        ).frame()

        val row = frame.screenRow(0)
        assertEquals(CellStyle(fg = CellColor.Rgb(0xFF6400), bg = CellColor.Rgb(0x010203)), row[0].style)
        assertEquals(CellColor.Default, row.last().style.fg)
    }

    /** The attributes of a cell become the attributes of its run. */
    @Test
    fun attributesAreCarriedIntoTheRun() {
        val frame = screenOf(
            snapshot(
                rows = listOf(
                    listOf(
                        VtCell("B", bold = true),
                        VtCell("U", underline = true),
                        VtCell("I", inverse = true),
                        VtCell("A", italic = true),
                        VtCell("D", dim = true),
                        VtCell("S", strike = true),
                    ),
                ),
            ),
        ).frame()

        val byText = frame.screenRow(0).associateBy { it.text }
        assertTrue(byText.getValue("B").style.bold)
        assertTrue(byText.getValue("U").style.underline)
        assertTrue(byText.getValue("I").style.inverse)
        assertTrue(byText.getValue("A").style.italic)
        assertTrue(byText.getValue("D").style.dim)
        assertTrue(byText.getValue("S").style.strike)
    }

    /** Neighbouring cells with the same attributes share a run; a change of attributes starts a new one. */
    @Test
    fun cellsWithTheSameStyleShareARun() {
        val frame = screenOf(
            snapshot(rows = listOf(cells("ab") + cells("cd") { it.copy(bold = true) })),
        ).frame()

        assertEquals(listOf("ab", "cd"), frame.screenRow(0).map { it.text })
        assertFalse(frame.screenRow(0)[0].style.bold)
        assertTrue(frame.screenRow(0)[1].style.bold)
    }

    /** The cursor's cell is a run of its own, marked as the cursor; the cursor line is the screen row's index in the frame. */
    @Test
    fun theCursorCellIsItsOwnRun() {
        val frame = screenOf(
            snapshot(rows = listOf(cells("abc")), cursorRow = 0, cursorCol = 1, cursorVisible = true),
        ).frame()

        val row = frame.screenRow(0)
        assertEquals(listOf("a", "b", "c"), row.map { it.text })
        assertEquals(listOf(false, true, false), row.map { it.cursor })
        assertEquals(frame.screenStart, frame.cursorLine)
    }

    /** A cursor past the text keeps its blank cell: the cursor is drawn where the program put it. */
    @Test
    fun aCursorOnTheBlankTailIsKept() {
        val frame = screenOf(snapshot(rows = listOf(cells("ab") + listOf(VtCell(""), VtCell(""))), cursorCol = 3, cursorVisible = true)).frame()

        val row = frame.screenRow(0)
        assertEquals(listOf("ab ", " "), row.map { it.text })
        assertTrue(row.last().cursor)
        assertEquals(3, row.last().col)
    }

    /** A hidden cursor is on no line, and no run is marked. */
    @Test
    fun aHiddenCursorIsNotMarked() {
        val frame = screenOf(snapshot(rows = listOf(cells("x")), cursorVisible = false)).frame()

        assertEquals(-1, frame.cursorLine)
        assertFalse(frame.lines.any { line -> line.any { it.cursor } })
    }

    /** Trailing blank cells are not drawn; the line keeps what has text in it. */
    @Test
    fun trailingBlanksAreLeftOut() {
        val frame = screenOf(snapshot(rows = listOf(cells("hi") + List(10) { VtCell("") })), cols = 12).frame()

        assertEquals("hi", frame.screenRow(0).joinToString("") { it.text })
    }

    /** A wide character is a run of its own that takes two columns; the next character starts after it. */
    @Test
    fun aWideCharacterIsARunOfTwoColumns() {
        val frame = screenOf(
            snapshot(rows = listOf(listOf(VtCell("中", wide = true), VtCell("", wideSpacer = true), VtCell("x")))),
        ).frame()

        val row = frame.screenRow(0)
        assertEquals(listOf("中", "x"), row.map { it.text })
        assertEquals(listOf(0, 2), row.map { it.col })
        assertEquals(listOf(2, 1), row.map { it.cells })
    }

    /** Each run starts at the column of its first cell and takes as many columns as its cells. */
    @Test
    fun runsCarryTheirColumns() {
        val frame = screenOf(snapshot(rows = listOf(cells("ab") + cells("c") { it.copy(bold = true) }))).frame()

        val row = frame.screenRow(0)
        assertEquals(listOf("ab", "c"), row.map { it.text })
        assertEquals(listOf(0, 2), row.map { it.col })
        assertEquals(listOf(2, 1), row.map { it.cells })
    }

    /** A concealed cell draws as a space, so its text is not on the screen. */
    @Test
    fun aConcealedCellShowsNoText() {
        val frame = screenOf(snapshot(rows = listOf(listOf(VtCell("s", conceal = true), VtCell("v"))))).frame()

        assertEquals(" v", frame.screenRow(0).joinToString("") { it.text })
    }

    /** History is above the screen rows, and the frame asks the engine for at most the lines it is given. */
    @Test
    fun historyIsAboveTheScreenAndTheEngineIsAskedForAtMostWhatIsGiven() {
        val engine = ScriptedEngine(
            snapshot(
                rows = listOf(cells("screen")),
                history = listOf(cells("one"), cells("two"), cells("three")),
            ),
        )
        val frame = VtScreen(20, 1, engine).frame(historyLines = 2)

        assertEquals(2, engine.lastMaxHistory)
        assertEquals(listOf("one", "two", "three", "screen"), frame.lines.map { line -> line.joinToString("") { it.text } })
        assertEquals(3, frame.screenStart)
    }

    /** A history line is converted once: the same line object comes back on the next frame. */
    @Test
    fun aHistoryLineIsConvertedOnce() {
        val history = listOf(cells("old line"))
        val engine = ScriptedEngine(snapshot(rows = listOf(cells("now")), history = history))
        val screen = VtScreen(20, 1, engine)

        val first = screen.frame().lines.first()
        val second = screen.frame().lines.first()

        assertSame(first, second)
    }

    /** The cursor's line index points at the cursor's row in the lines the frame carries. */
    @Test
    fun theCursorLineFollowsTheHistory() {
        val frame = screenOf(
            snapshot(rows = listOf(cells("a"), cells("b")), history = listOf(cells("h")), cursorRow = 1, cursorCol = 0, cursorVisible = true),
        ).frame()

        assertEquals(2, frame.cursorLine)
        assertEquals("b", frame.lines[frame.cursorLine].joinToString("") { it.text })
    }

    /** Bytes go to the engine in order, and an empty write goes nowhere. */
    @Test
    fun bytesGoToTheEngineInOrder() {
        val engine = ScriptedEngine(snapshot(rows = listOf(cells(""))))
        val screen = VtScreen(4, 1, engine)

        screen.write("a".toByteArray())
        screen.write(ByteArray(0))
        screen.write("b".toByteArray())

        assertEquals(listOf("a", "b"), engine.writes.map { String(it) })
    }

    /** A resize goes to the engine, and the frame reports the new size. */
    @Test
    fun aResizeReachesTheEngineAndTheFrame() {
        val engine = ScriptedEngine(snapshot(rows = listOf(cells(""))))
        val screen = VtScreen(4, 1, engine)

        screen.resize(60, 10)

        assertEquals(listOf(60 to 10), engine.resizes)
        assertEquals(60, screen.cols)
        assertEquals(10, screen.rows)
    }

    /** A size that is not at least one cell is refused before the engine sees it. */
    @Test(expected = IllegalArgumentException::class)
    fun aZeroSizeIsRefused() {
        VtScreen(4, 1, ScriptedEngine(snapshot(rows = listOf(cells(""))))).resize(0, 1)
    }

    private fun TerminalFrame.screenRow(row: Int): List<CellRun> = lines[screenStart + row]
}
