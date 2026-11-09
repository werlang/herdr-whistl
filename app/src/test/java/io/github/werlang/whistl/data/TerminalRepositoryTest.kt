package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.TerminalFrame
import io.github.werlang.whistl.support.RecordingLog
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [DefaultTerminalRepository] over the fake core: the send rules and the gone terminal (as before), and the output stream (SPEC
 * §5.1): what the program writes reaches the screen, a resize goes out as a RESIZE frame, a dropped stream re-attaches with
 * `resumed` and keeps the screen, every DATA frame is credited, and a flood only ever shows whole frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {

    /** The stream's log lines go to the recording sink, so the tests can read what was logged. */
    @get:Rule
    val log = RecordingLog()

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    /** Opens terminal `t1` on the connected machine `m1`, after [terminal] is put on the fake core. */
    private suspend fun TestScope.openOn(terminal: JsonObject?, screen: String = ""): Pair<RepoStack, TerminalHandle> {
        val h = RepoStack(this)
        if (terminal != null) h.core.terminals += terminal
        h.core.screens["t1"] = screen
        h.addMachine()
        runCurrent()
        val handle = h.terminals.open("m1", "t1")
        runCurrent()
        return h to handle
    }

    /** A blocked program refuses unforced text and takes forced text. */
    @Test
    fun aBlockedProgramRefusesUnforcedTextAndTakesForcedText() = runTest {
        val (_, handle) = openOn(terminalJson("t1", status = "blocked"))

        assertEquals(TerminalAction.Blocked, handle.send("y", force = false))
        assertEquals(TerminalAction.Done, handle.send("y", force = true))
        handle.close()
    }

    /** A terminal that is not on the machine reads as gone, and actions on it say so. */
    @Test
    fun aMissingTerminalIsGone() = runTest {
        val (_, handle) = openOn(terminal = null)
        runCurrent()

        assertEquals(TerminalReach.Gone, handle.screen.value.reach)
        assertEquals(TerminalAction.Gone, handle.send("x", force = false))
        handle.close()
    }

    /** Stop and remove sends `terminal.kill` before `terminal.rm`. */
    @Test
    fun stopAndRemoveKillsBeforeItRemoves() = runTest {
        val (h, handle) = openOn(terminalJson("t1"))

        assertEquals(TerminalAction.Done, handle.stopAndRemove())

        assertEquals(listOf("terminal.kill", "terminal.rm"), h.core.methods.filter { it.startsWith("terminal.k") || it == "terminal.rm" })
        assertEquals(listOf("t1"), h.core.removedTerminals)
        handle.close()
    }

    /**
     * The stream's log lines name the channel, the size and whether the attach resumed: numbers and words only. The text the program
     * wrote, the host and the user never reach them.
     */
    @Test
    fun theStreamLogsItsStepsWithoutAnyContent() = runTest {
        val (_, handle) = openOn(terminalJson("t1"), screen = "secret screen text")
        advanceTimeBy(100)
        runCurrent()

        val messages = log.lines.map { it.message }
        assertTrue("the attach is logged: $messages", messages.any { it == "stream attached: channel 1, 80 x 24, resumed=false" })
        assertTrue("nothing logged carries the screen text", messages.none { it.contains("secret") })
        assertTrue("nothing logged carries the host", messages.none { it.contains("m1") || it.contains("t1") })
        handle.close()
    }

    /** The screen shows what the program wrote, from the first paint on: the attach is on a framed stream, not a read. */
    @Test
    fun theScreenShowsWhatTheProgramWrote() = runTest {
        val (h, handle) = openOn(terminalJson("t1"), screen = "line one")
        advanceTimeBy(100)
        runCurrent()

        assertEquals("line one", handle.screen.value.frame.plainText().trimEnd())
        assertEquals(TerminalReach.Connected, handle.screen.value.reach)
        assertEquals(1, h.connector.streams.size)
        handle.close()
    }

    /** The stream attaches at the size the pane has, and a later resize goes out as a RESIZE frame and reaches the screen. */
    @Test
    fun aResizeReachesTheCoreAsAResizeFrame() = runTest {
        val (h, handle) = openOn(terminalJson("t1"))
        val stream = h.connector.streams.last()
        assertEquals(80, stream.attaches.single().getValue("cols").jsonPrimitive.content.toInt())

        handle.resize(100, 30)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(100 to 30), stream.resizes)
        assertEquals(100, handle.screen.value.frame.cols)
        assertEquals(30, handle.screen.value.frame.rows)
        handle.close()
    }

    /**
     * A dropped stream re-attaches with `resumed` (the screen is not started again), and the text it had is still there after the
     * re-attach; the screen goes offline while the stream is down.
     */
    @Test
    fun aDroppedStreamReattachesWithResumedAndKeepsTheScreen() = runTest {
        val (h, handle) = openOn(terminalJson("t1"), screen = "keep me")
        advanceTimeBy(100)
        runCurrent()
        assertEquals("keep me", handle.screen.value.frame.plainText().trimEnd())

        h.connector.streams.last().end()
        runCurrent()
        assertEquals(TerminalReach.Offline, handle.screen.value.reach)

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(2, h.connector.streams.size)
        val reattach = h.connector.streams.last().attaches.single()
        assertEquals(true, reattach.getValue("resumed").jsonPrimitive.content.toBoolean())
        assertEquals(TerminalReach.Connected, handle.screen.value.reach)
        assertEquals("keep me", handle.screen.value.frame.plainText().trimEnd())
        handle.close()
    }

    /** Each DATA frame is credited with its length once the emulator has taken it, so the core keeps sending. */
    @Test
    fun everyDataFrameIsCreditedOnceHandled() = runTest {
        val (h, handle) = openOn(terminalJson("t1"), screen = "line one")
        val stream = h.connector.streams.last()

        stream.send("xyz".toByteArray())
        runCurrent()

        assertEquals(listOf(8L, 3L), stream.credits)
        handle.close()
    }

    /**
     * Output that outruns the redraw: every frame the screen publishes is one whole state of the emulator (its lines and the cursor
     * agree), and the last frame has the last line. Nothing the core sent is lost, and the credits cover every byte.
     */
    @Test
    fun aFloodOnlyPublishesWholeFrames() = runTest {
        val (h, handle) = openOn(terminalJson("t1"))
        val stream = h.connector.streams.last()
        var sent = 0L
        val frames = mutableListOf<TerminalFrame>()

        repeat(300) { chunk ->
            val text = (0 until 10).joinToString("") { "row ${chunk * 10 + it}: ${"x".repeat(30)}\r\n" }
            stream.send(text.toByteArray())
            sent += text.length
            advanceTimeBy(7)
            runCurrent()
            frames += handle.screen.value.frame
        }
        advanceTimeBy(100)
        runCurrent()

        frames.forEach { frame ->
            assertEquals("lines are history then screen rows", frame.screenStart + frame.rows, frame.lines.size)
            assertTrue("the cursor is on a screen row or hidden", frame.cursorLine == -1 || frame.cursorLine >= frame.screenStart)
            if (frame.cursorLine >= 0) assertTrue(frame.lines[frame.cursorLine].any { it.cursor })
        }
        assertTrue("the newest line is on the screen", handle.screen.value.frame.plainText().contains("row 2999"))
        assertEquals(sent, stream.credits.sum())
        handle.close()
    }
}
