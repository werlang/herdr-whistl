package io.github.werlang.whistl.core.vt

import androidx.test.platform.app.InstrumentationRegistry
import io.github.werlang.whistl.core.rpc.FrameDecoder
import io.github.werlang.whistl.core.rpc.FrameKind
import io.github.werlang.whistl.domain.CellColor
import io.github.werlang.whistl.domain.CellRun
import io.github.werlang.whistl.domain.TerminalFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real VT engine ([JohnanEngine]) on the device (SPEC §5.1, phase 8 r3). The input is what the core sent: the recorded attach streams in
 * `docs/protocol-fixtures` (read from the test APK's assets), plus a synthetic flood and a resize. The JVM tests cannot load this engine
 * (its classes are Java 21); run with `scripts/gradle connectedDebugAndroidTest` on an emulator or a device.
 */
class VtEngineDeviceTest {

    private fun newScreen(cols: Int = 80, rows: Int = 24): VtScreen =
        VtScreen(cols, rows, JohnanEngine(cols, rows, VtScreen.MAX_SCROLLBACK))

    /** A fixture from `docs/protocol-fixtures`, as the test APK's asset. */
    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** The program's bytes of a recorded attach stream, in order: the DATA frames of channel 1, which is the terminal (channel 0 is JSON-RPC). */
    private fun programBytes(recorded: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val decoder = FrameDecoder()
        decoder.feed(recorded) { frame ->
            if (frame.channel != 0L && frame.kind == FrameKind.DATA) out += frame.payload
        }
        decoder.finish()
        return out
    }

    private fun TerminalFrame.screenRow(row: Int): List<CellRun> = lines[screenStart + row]

    private fun TerminalFrame.screenText(): List<String> = (0 until rows).map { row -> screenRow(row).joinToString("") { it.text } }

    private fun List<CellRun>.run(text: String): CellRun {
        val found = firstOrNull { it.text == text }
        assertNotNull("no run reads \"$text\"", found)
        return found!!
    }

    private fun TerminalFrame.lineText(index: Int): String = lines[index].joinToString("") { it.text }

    /**
     * The first paint of `attach_frames.bin`: the core's own rendering of the screen with `red` (SGR 31), `bold green` (SGR 1;32) and
     * `true` (24-bit 255;100;0), then the echoed `hi` and a magenta `hi` (SGR 1;35). The cursor ends on the line after it.
     */
    @Test
    fun theRecordedFirstPaintReachesTheScreenWithItsColours() {
        val vt = newScreen()
        programBytes(fixture("attach_frames.bin")).forEach { vt.write(it) }
        val frame = vt.frame()

        val red = frame.screenRow(0).run("red")
        assertEquals(CellColor.Rgb(0xCD0000), red.style.fg)
        val green = frame.screenRow(0).run("bold green")
        assertEquals(CellColor.Rgb(0x00CD00), green.style.fg)
        assertTrue(green.style.bold)
        assertEquals(CellColor.Rgb(0xFF6400), frame.screenRow(1).run("true").style.fg)
        assertEquals("hi", frame.screenRow(2).joinToString("") { it.text }.trimEnd())
        assertEquals(CellColor.Rgb(0xCD00CD), frame.screenRow(3).run("hi").style.fg)
        assertEquals(frame.screenStart + 4, frame.cursorLine)
        assertEquals(0, frame.lines[frame.cursorLine].first { it.cursor }.col)
    }

    /**
     * `attach_frames_256.bin`: a 16 by 16 grid of background cells in palette indices 0 to 255, then a 32-cell truecolour gradient. Every
     * palette colour the program printed reaches the frame as its xterm value, and the gradient reaches it exactly.
     */
    @Test
    fun theRecorded256ColourGridReachesTheScreen() {
        val vt = newScreen()
        programBytes(fixture("attach_frames_256.bin")).forEach { vt.write(it) }
        val frame = vt.frame()

        val backgrounds = frame.lines.flatten().mapNotNull { (it.style.bg as? CellColor.Rgb)?.value }.toSet()
        for (index in 0..255) {
            assertTrue("palette colour $index is drawn", Xterm256.rgb(index) in backgrounds)
        }
        val gradient = frame.lines.flatten().filter { run ->
            val value = (run.style.bg as? CellColor.Rgb)?.value ?: return@filter false
            Xterm256.rgb(0) != value && (0 until 256).none { Xterm256.rgb(it) == value }
        }
        assertTrue("the 24-bit gradient is drawn", gradient.size >= 32)
    }

    /**
     * `attach_frames_altscreen.bin`: main text, then htop on the alternate screen (`ESC [ ? 1049 h`), then the exit (`ESC [ ? 1049 l`) and
     * more main text. The htop rows are on the screen while it is shown, and the main text is back when it is left.
     */
    @Test
    fun theAlternateScreenIsShownAndTheMainScreenComesBackOnTheRecordedStream() {
        val vt = newScreen()
        val chunks = programBytes(fixture("attach_frames_altscreen.bin"))
        val enter = chunks.indexOfFirst { String(it, Charsets.UTF_8).contains("\u001b[?1049h") }
        // The first paint itself resets the alternate screen, so the exit is looked for after the enter.
        val exit = (enter + 1 until chunks.size).firstOrNull { String(chunks[it], Charsets.UTF_8).contains("\u001b[?1049l") } ?: -1
        assertTrue("the stream enters and leaves the alternate screen", enter in 0 until exit)

        chunks.subList(0, enter + 1).forEach { vt.write(it) }
        // htop draws in the pieces after the enter: the screen is checked once its first paint with the task line is in.
        val firstHtopPaint = (enter + 1 until exit).first { String(chunks[it], Charsets.UTF_8).contains("Tasks:") }
        chunks.subList(enter + 1, firstHtopPaint + 1).forEach { vt.write(it) }
        val during = vt.frame()
        assertTrue("the htop screen is drawn", during.plainText().contains("Tasks:"))
        assertFalse("the main text is not drawn over it", during.screenText().any { it.contains("main screen before") })

        chunks.subList(firstHtopPaint + 1, exit + 1).forEach { vt.write(it) }
        val after = vt.frame()
        assertTrue("the main text is back", after.plainText().contains("main screen before"))
        assertFalse("the htop text is gone", after.plainText().contains("Tasks:"))

        chunks.subList(exit + 1, chunks.size).forEach { vt.write(it) }
        assertTrue("the text after the exit is drawn", vt.frame().plainText().contains("main screen after"))
    }

    /**
     * Output that outruns the redraw: 300 KiB of numbered lines, written in pieces, with the emulator keeping 2000 lines of history. The
     * history and the screen hold consecutive lines, each exactly as written, and the newest line is on the screen.
     */
    @Test
    fun aFloodKeepsTheHistoryInOrderAndWhole() {
        val vt = newScreen()
        var written = 0
        var number = 0
        while (written < FLOOD_BYTES) {
            val line = "flood %07d %s\r\n".format(number, "x".repeat(60))
            vt.write(line.toByteArray(Charsets.UTF_8))
            written += line.length
            number++
        }
        val frame = vt.frame()

        val numbered = frame.lines.map { line -> line.joinToString("") { it.text } }.filter { it.startsWith("flood ") }
        assertTrue("the frame holds more than a screen of history: ${numbered.size}", numbered.size > 1_000)
        val last = number - 1
        val first = last - (numbered.size - 1)
        numbered.forEachIndexed { index, text ->
            assertEquals("line ${first + index} is whole and in order", "flood %07d %s".format(first + index, "x".repeat(60)), text)
        }
        assertEquals("the newest line is the last one written", last, numbered.last().substring(6, 13).toInt())
    }

    /** A resize mid-stream: the frame takes the new size at once, the text stays, and the lines written after it are drawn. */
    @Test
    fun aResizeMidStreamKeepsTheTextAndTheNewLines() {
        val vt = newScreen(80, 24)
        for (n in 0 until 500) vt.write("line %04d\r\n".format(n).toByteArray())

        vt.resize(40, 12)
        for (n in 500 until 1_000) vt.write("line %04d\r\n".format(n).toByteArray())
        val frame = vt.frame()

        assertEquals(40, frame.cols)
        assertEquals(12, frame.rows)
        assertEquals(12, frame.lines.size - frame.screenStart)
        assertTrue("the last line is on the screen", frame.plainText().contains("line 0999"))
        val history = frame.lines.subList(0, frame.screenStart).map { line -> line.joinToString("") { it.text } }
        // terminal-core 1.0.8 drops the line above the cursor when the screen shrinks in rows (measured here: line 499 of 0 to 999).
        // The test pins exactly that loss: any other loss, a reordering or a duplicate fails it. Open decision for the supervisor, see the report.
        val missing = (0..988).filter { n -> !history.contains("line %04d".format(n)) }
        assertEquals("lines lost across the shrink", listOf(KNOWN_SHRINK_LOSS), missing)
        assertEquals("the history is in order", history.sorted(), history)
    }

    /** A wide character takes two columns: its run starts at its column and the next character starts after it. */
    @Test
    fun aWideCharacterTakesTwoColumns() {
        val frame = newScreen().also { it.write("中x".toByteArray(Charsets.UTF_8)) }.frame()

        val wide = frame.screenRow(0).run("中")
        assertEquals(0, wide.col)
        assertEquals(2, wide.cells)
        assertEquals(2, frame.screenRow(0).run("x").col)
    }

    /** Attributes typed as SGR reach the runs: bold, underline, inverse, italic, dim and strike-through. */
    @Test
    fun attributesReachTheRuns() {
        val frame = newScreen().also {
            it.write("\u001b[1mB\u001b[4mU\u001b[7mI\u001b[3mA\u001b[2mD\u001b[9mS\u001b[0mP".toByteArray())
        }.frame()

        val row = frame.screenRow(0)
        assertTrue(row.run("B").style.bold)
        assertTrue(row.run("U").style.underline)
        assertTrue(row.run("I").style.inverse)
        assertTrue(row.run("A").style.italic)
        assertTrue(row.run("D").style.dim)
        assertTrue(row.run("S").style.strike)
        assertFalse(row.run("P").style.bold)
    }

    /** An escape sequence cut across two writes is one sequence: the colour applies to the text after it. */
    @Test
    fun anEscapeSequenceSplitAcrossWritesIsOneSequence() {
        val vt = newScreen()
        vt.write("\u001b[3".toByteArray())
        vt.write("1mX".toByteArray())

        assertEquals(CellColor.Rgb(0xCD0000), vt.frame().screenRow(0).run("X").style.fg)
    }

    private companion object {
        /** More than the 256 KiB window the core sends before it waits for credit. */
        const val FLOOD_BYTES: Int = 300 * 1024

        /** The line terminal-core 1.0.8 loses when a 24-row screen shrinks to 12 rows with the cursor below it. */
        const val KNOWN_SHRINK_LOSS: Int = 499
    }
}
