package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.vt.VtCell
import io.github.werlang.whistl.core.vt.VtEngine
import io.github.werlang.whistl.core.vt.VtRow
import io.github.werlang.whistl.core.vt.VtScreen
import io.github.werlang.whistl.core.vt.VtSnapshot

/**
 * A [VtEngine] for the JVM tests, which cannot load the device emulator (its classes are Java 21). It knows text, CR and LF, and
 * nothing else: an escape sequence is taken as text. The tests of the stream use it for what they check (what was written, where
 * the cursor is, what the history keeps); the escape sequences are checked on the device, in `app/src/androidTest`.
 *
 * @param cols the width in cells.
 * @param rows the height in cells.
 * @param maxScrollback how many history lines are kept.
 */
class PlainTextEngine(
    private var cols: Int,
    private var rows: Int,
    private val maxScrollback: Int = VtScreen.MAX_SCROLLBACK,
) : VtEngine {
    private val history = ArrayDeque<VtRow>()
    private val screen = MutableList(rows) { mutableListOf<VtCell>() }
    private var cursorRow = 0
    private var cursorCol = 0

    override fun write(bytes: ByteArray) {
        for (ch in String(bytes, Charsets.UTF_8)) {
            when (ch) {
                '\r' -> cursorCol = 0
                '\n' -> lineFeed()
                else -> put(ch)
            }
        }
    }

    override fun resize(cols: Int, rows: Int) {
        this.cols = cols
        while (screen.size < rows) screen.add(mutableListOf<VtCell>())
        while (screen.size > rows) screen.removeAt(screen.size - 1)
        this.rows = rows
        cursorRow = cursorRow.coerceAtMost(rows - 1)
        cursorCol = cursorCol.coerceAtMost(cols - 1)
    }

    override fun snapshot(maxHistory: Int): VtSnapshot = VtSnapshot(
        history = history.toList().takeLast(maxHistory),
        rows = screen.map { VtRow(it.toList()) },
        cursorRow = cursorRow,
        cursorCol = cursorCol,
        cursorVisible = true,
    )

    private fun put(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        val line = screen[cursorRow]
        while (line.size < cursorCol) line += VtCell("")
        if (line.size == cursorCol) line += VtCell(ch.toString()) else line[cursorCol] = VtCell(ch.toString())
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++
            return
        }
        history.addLast(VtRow(screen.removeAt(0).toList()))
        while (history.size > maxScrollback) history.removeFirst()
        screen.add(mutableListOf<VtCell>())
    }
}

/** A screen over a [PlainTextEngine], for the JVM tests of the stream. */
fun plainTextScreen(cols: Int, rows: Int): VtScreen = VtScreen(cols, rows, PlainTextEngine(cols, rows))
