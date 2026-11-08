package io.github.werlang.whistl.ui.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.werlang.whistl.data.DefaultSettingsRepository
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.support.InMemorySettingsStore
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Agent screen's view model over the real repositories and the fake core (phase 4 r2, §12). Each test reads what the
 * screen would send, from the requests the fake core received.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** The method of a recorded request. */
    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    /** The params of a recorded request. */
    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    /** The `keys` a `terminal.keys` request sends. */
    private fun JsonObject.keyNames(): List<String> = params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }

    /** The requests of one method, in order. */
    private fun RepoStack.sent(method: String): List<JsonObject> = core.requests.filter { it.method() == method }

    /**
     * A screen on terminal `t1` of the connected machine `m1`, with [screen] as its output.
     *
     * @return the stack, the view model, and the settings it reads.
     */
    private suspend fun TestScope.openAgent(
        status: String? = null,
        state: String = "live",
        screen: String = "",
        settings: DefaultSettingsRepository = DefaultSettingsRepository(InMemorySettingsStore()),
    ): Triple<RepoStack, AgentViewModel, DefaultSettingsRepository> {
        val h = RepoStack(this)
        val terminal = terminalJson("t1", state = state, status = status)
        h.core.terminals += terminal
        h.core.screens["t1"] = screen
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, listOf(terminal))) }
        h.addMachine()
        runCurrent()
        val vm = AgentViewModel(h.terminals, h.agents, h.machines, settings, "m1", "t1")
        runCurrent()
        return Triple(h, vm, settings)
    }

    /** While connected, the output is read and shown, and the field takes input. */
    @Test
    fun theOutputIsReadAndShownWhileConnected() = runTest {
        val (_, vm, _) = openAgent(screen = "line one")

        val state = vm.state.value
        assertEquals("line one", state.screenText)
        assertEquals(TerminalReach.Connected, state.reach)
        assertTrue(state.canType)
    }

    /** The output is read again every 500 ms of time while the screen is open. */
    @Test
    fun theOutputUpdatesOnThePoll() = runTest {
        val (h, vm, _) = openAgent(screen = "one")

        h.core.screens["t1"] = "two"
        advanceTimeBy(500)
        runCurrent()

        assertEquals("two", vm.state.value.screenText)
    }

    /** Send sends the text and presses Enter. The request has no `force` field, and the field is cleared. */
    @Test
    fun sendSendsTheTextWithoutForceAndClearsTheField() = runTest {
        val (h, vm, _) = openAgent()

        vm.onInputChange(TextFieldValue("ls -la"))
        vm.onSend()
        runCurrent()

        val send = h.sent("terminal.send").single()
        assertEquals("ls -la", send.params().getValue("text").jsonPrimitive.content)
        assertNull(send.params()["force"])
        assertEquals("", vm.state.value.input.text)
    }

    /** A blocked agent is waiting for the user, so Send forces the text in; the status and the explanation say so. */
    @Test
    fun aBlockedAgentReceivesTheTextForced() = runTest {
        val (h, vm, _) = openAgent(status = "blocked")
        assertTrue(vm.state.value.blockedAgent)

        vm.onInputChange(TextFieldValue("y"))
        vm.onSend()
        runCurrent()

        val send = h.sent("terminal.send").single()
        assertEquals(true, send.params().getValue("force").jsonPrimitive.content.toBoolean())
        assertNull(vm.state.value.notice)
    }

    /** Ctrl armed, then a typed letter: the letter goes out as `ctrl+<letter>`, is not inserted, and Ctrl disarms. */
    @Test
    fun ctrlThenALetterSendsTheCtrlLetterAndInsertsNothing() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        assertTrue(vm.state.value.ctrlArmed)
        vm.onInputChange(TextFieldValue("X"))
        runCurrent()

        assertEquals(listOf("ctrl+x"), h.sent("terminal.keys").single().keyNames())
        assertEquals("", vm.state.value.input.text)
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed, then a bar key with a Ctrl combination: the combination goes out, and Ctrl disarms. */
    @Test
    fun ctrlThenACombinationKeySendsTheCombination() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onKey(ShortcutKey.CTRL_C)
        runCurrent()

        assertEquals(listOf("ctrl+c"), h.sent("terminal.keys").single().keyNames())
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed, then a key with no Ctrl combination: the key goes out as it is, and Ctrl disarms. */
    @Test
    fun ctrlThenAnArrowSendsTheArrowAndDisarms() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onKey(ShortcutKey.UP)
        runCurrent()

        assertEquals(listOf("up"), h.sent("terminal.keys").single().keyNames())
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed, then a typed character that is not a letter: it is inserted, and Ctrl disarms. */
    @Test
    fun ctrlThenATypedDigitIsInsertedAndDisarms() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("1"))

        assertEquals("1", vm.state.value.input.text)
        assertFalse(vm.state.value.ctrlArmed)
        assertTrue(h.sent("terminal.keys").isEmpty())
    }

    /** `/ | ~ -` insert their character at the cursor, one after the other; none of them is sent as a key. */
    @Test
    fun slashPipeTildeAndDashInsertAtTheCursor() = runTest {
        val (h, vm, _) = openAgent()
        vm.onInputChange(TextFieldValue("ab", TextRange(1)))

        vm.onKey(ShortcutKey.SLASH)
        assertEquals("a/b", vm.state.value.input.text)
        assertEquals(2, vm.state.value.input.selection.start)
        vm.onKey(ShortcutKey.PIPE)
        assertEquals("a/|b", vm.state.value.input.text)
        vm.onKey(ShortcutKey.TILDE)
        assertEquals("a/|~b", vm.state.value.input.text)
        vm.onKey(ShortcutKey.MINUS)
        assertEquals("a/|~-b", vm.state.value.input.text)
        runCurrent()

        assertTrue(h.sent("terminal.keys").isEmpty())
    }

    /** Any other bar key is sent at once with `terminal.keys`, under its hc name. */
    @Test
    fun aBarKeyIsSentAtOnce() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.ESC)
        runCurrent()

        assertEquals(listOf("esc"), h.sent("terminal.keys").single().keyNames())
    }

    /** Interrupt sends Ctrl+C through `terminal.interrupt`. */
    @Test
    fun interruptSendsCtrlC() = runTest {
        val (h, vm, _) = openAgent()

        vm.onOpenMenu()
        vm.onInterrupt()
        runCurrent()

        assertEquals(1, h.sent("terminal.interrupt").size)
        assertEquals(SentKey("^C"), vm.state.value.lastKey)
        assertFalse(vm.state.value.menuOpen)
    }

    /** Stop and remove asks first; once confirmed it kills the terminal, then removes it, then the screen closes. */
    @Test
    fun stopAndRemoveKillsThenRemovesThenCloses() = runTest {
        val (h, vm, _) = openAgent()

        vm.onAskStop()
        assertTrue(vm.state.value.confirmStop)
        vm.onConfirmStop()
        runCurrent()

        assertEquals(
            listOf("terminal.kill", "terminal.rm"),
            h.core.methods.filter { it == "terminal.kill" || it == "terminal.rm" },
        )
        assertEquals(listOf("t1"), h.core.removedTerminals)
        assertTrue(vm.state.value.closed)
    }

    /** A machine that drops leaves the input disabled with the offline notice; Send does nothing. */
    @Test
    fun anOfflineMachineDisablesInputAndSend() = runTest {
        val (h, vm, _) = openAgent()

        h.connector.lastSession!!.kill()
        advanceTimeBy(500)
        runCurrent()
        vm.onInputChange(TextFieldValue("x"))
        vm.onSend()
        runCurrent()

        val state = vm.state.value
        assertEquals(TerminalReach.Offline, state.reach)
        assertFalse(state.canType)
        assertFalse(state.canSend)
        assertTrue(h.sent("terminal.send").isEmpty())
    }

    /** Leaving the screen stops the reads: no `terminal.read` is sent during the next ten seconds. */
    @Test
    fun closingTheScreenStopsThePolling() = runTest {
        val (h, vm, _) = openAgent()
        val reads = h.core.methods.count { it == "terminal.read" }

        vm.onLeave()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(reads, h.core.methods.count { it == "terminal.read" })
    }

    /** A finished program is read-only. */
    @Test
    fun aFinishedAgentIsReadOnly() = runTest {
        val (_, vm, _) = openAgent(state = "finished")

        val state = vm.state.value
        assertFalse(state.canType)
        assertEquals(io.github.werlang.whistl.ui.agent.AgentNotice.Finished, state.notice)
        assertEquals(AgentStatusLabel.Finished, state.statusLabel)
    }

    /** A blocked agent is labelled as needing the user. */
    @Test
    fun aBlockedAgentIsLabelledNeedsYou() = runTest {
        val (_, vm, _) = openAgent(status = "blocked")

        assertEquals(AgentStatusLabel.NeedsYou, vm.state.value.statusLabel)
    }

    /**
     * Phase 6 P0-2, call site `terminals.open` in the Agent view model: a machine that is no longer added makes the open throw.
     * The screen must show the terminal as gone and offer no typing, and the view model must not crash.
     */
    @Test
    fun openingATerminalOfAnUnknownMachineShowsItAsGone() = runTest {
        val h = RepoStack(this)
        val settings = DefaultSettingsRepository(InMemorySettingsStore())
        val vm = AgentViewModel(h.terminals, h.agents, h.machines, settings, "no-such-machine", "t1")
        runCurrent()

        assertEquals(AgentNotice.Gone, vm.state.value.notice)
        assertFalse("nothing can be typed into a terminal that is gone", vm.state.value.canType)
    }
}
