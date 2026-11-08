package io.github.werlang.whistl.data

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
import org.junit.Test

/** [DefaultTerminalRepository] over the fake core: the read, the send rules, the gone terminal and the removal order. */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    /** Opens terminal `t1` on the connected machine `m1`, after [terminal] is put on the fake core. */
    private suspend fun TestScope.openOn(terminal: JsonObject?): Pair<RepoStack, TerminalHandle> {
        val h = RepoStack(this)
        if (terminal != null) h.core.terminals += terminal
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

    /** The output is read with the last 200 lines asked for, and every 500 ms while open. */
    @Test
    fun theOutputIsReadWithTheLastLinesAskedFor() = runTest {
        val (h, handle) = openOn(terminalJson("t1"))
        advanceTimeBy(500)
        runCurrent()

        val reads = h.core.requests.filter { it.method() == "terminal.read" }
        assertTrue(reads.size >= 2)
        assertEquals(200, reads.first().getValue("params").jsonObject.getValue("lines").jsonPrimitive.content.toInt())
        handle.close()
    }
}
