package io.github.werlang.whistl.ui.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.werlang.whistl.data.DefaultSettingsRepository
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.ShortcutKey
import io.github.werlang.whistl.support.InMemorySettingsStore
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RecordingLog
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.spaceJson
import io.github.werlang.whistl.support.spacesDocJson
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

    /** The stream's log lines go to the recording sink, not to the Android stub. */
    @get:Rule
    val log = RecordingLog()

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
        advanceTimeBy(100)
        runCurrent()
        return Triple(h, vm, settings)
    }

    /** While connected, the output is read and shown, and the field takes input. */
    @Test
    fun theOutputIsReadAndShownWhileConnected() = runTest {
        val (_, vm, _) = openAgent(screen = "line one")

        val state = vm.state.value
        assertEquals("line one", state.frame.plainText().trimEnd())
        assertEquals(TerminalReach.Connected, state.reach)
        assertTrue(state.canType)
    }

    /** What the program writes after the screen opens reaches the screen, on the stream the screen holds (SPEC §5.1). */
    @Test
    fun theOutputFollowsWhatTheProgramWrites() = runTest {
        val (h, vm, _) = openAgent(screen = "one")

        h.connector.streams.last().send("\r\ntwo".toByteArray())
        advanceTimeBy(100)
        runCurrent()

        assertEquals("one\ntwo", vm.state.value.frame.plainText().trimEnd())
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
        // The first send also teaches the hold gesture; it is a hint, not a failure (0.6.2).
        assertEquals(AgentNotice.HoldToSendKeys, vm.state.value.notice)
    }

    /**
     * Ctrl armed on an empty field, then one typed letter: `ctrl+s` goes out, the letter is not inserted, Ctrl disarms, and the
     * hint says "Ctrl+S" (phase 7 r9, user decision).
     */
    @Test
    fun ctrlThenOneTypedLetterOnAnEmptyFieldSendsCtrlLetter() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        assertTrue(vm.state.value.ctrlArmed)
        vm.onInputChange(TextFieldValue("s"))
        runCurrent()

        assertEquals(listOf("ctrl+s"), h.sent("terminal.keys").single().keyNames())
        assertTrue(h.sent("terminal.send").isEmpty())
        assertEquals("", vm.state.value.input.text)
        assertFalse(vm.state.value.ctrlArmed)
        assertEquals(SentKey("S", ctrl = true), vm.state.value.lastKey)
    }

    /** Ctrl armed on an empty field, then an uppercase letter: the lowercase combination goes out, and the hint says "S". */
    @Test
    fun ctrlThenAnUppercaseLetterSendsTheLowercaseCombination() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("S"))
        runCurrent()

        assertEquals(listOf("ctrl+s"), h.sent("terminal.keys").single().keyNames())
        assertEquals(SentKey("S", ctrl = true), vm.state.value.lastKey)
        assertEquals("", vm.state.value.input.text)
    }

    /** Ctrl armed with text in the field, then a typed letter: it is inserted, nothing is sent, and Ctrl stays armed. */
    @Test
    fun ctrlTakesTheNextLetterEvenWithTextInTheField() = runTest {
        val (h, vm, _) = openAgent()

        vm.onInputChange(TextFieldValue("l"))
        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("ls"))
        runCurrent()

        // The `s` is the combination, not a character of the line: the field keeps what was already typed (0.6.2).
        assertEquals("l", vm.state.value.input.text)
        assertEquals(listOf("ctrl+s"), h.sent("terminal.keys").single().keyNames())
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed on an empty field, then a pasted word (more than one character): it is inserted, and Ctrl stays armed. */
    @Test
    fun ctrlThenAPastedWordIsInsertedAndStaysArmed() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("sleep"))
        runCurrent()

        assertEquals("sleep", vm.state.value.input.text)
        assertTrue(vm.state.value.ctrlArmed)
        assertTrue(h.sent("terminal.keys").isEmpty())
    }

    /** Ctrl armed while offline: the field takes no input, so nothing is sent and Ctrl stays armed. */
    @Test
    fun ctrlThenATypedLetterWhileOfflineIsInsertedAndStaysArmed() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        h.connector.lastSession!!.kill()
        advanceTimeBy(500)
        runCurrent()
        vm.onInputChange(TextFieldValue("s"))
        runCurrent()

        assertFalse(vm.state.value.canType)
        assertEquals("s", vm.state.value.input.text)
        assertTrue(vm.state.value.ctrlArmed)
        assertTrue(h.sent("terminal.keys").isEmpty())
    }

    /** Ctrl armed, then Send of text already in the field: the text goes out plainly, no Ctrl key is sent, and Ctrl disarms. */
    @Test
    fun ctrlThenSendSendsThePlainTextAndNeverCtrl() = runTest {
        val (h, vm, _) = openAgent()

        vm.onInputChange(TextFieldValue("ls"))
        vm.onKey(ShortcutKey.CTRL)
        vm.onSend()
        runCurrent()

        assertTrue(h.sent("terminal.keys").isEmpty())
        assertEquals("ls", h.sent("terminal.send").single().params().getValue("text").jsonPrimitive.content)
        assertEquals("", vm.state.value.input.text)
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed, then Send of longer text: the text goes out unmodified with Enter, no Ctrl key is sent, and Ctrl disarms. */
    @Test
    fun ctrlThenSendOfLongerTextSendsItUnmodifiedAndDisarms() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("sleep 100"))
        vm.onSend()
        runCurrent()

        assertTrue(h.sent("terminal.keys").isEmpty())
        val send = h.sent("terminal.send").single()
        assertEquals("sleep 100", send.params().getValue("text").jsonPrimitive.content)
        assertFalse(vm.state.value.ctrlArmed)
    }

    /** Ctrl armed, then Send of one character with no Ctrl combination (a digit): it goes out unmodified, and Ctrl disarms. */
    @Test
    fun ctrlThenSendOfASingleDigitSendsItUnmodified() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("1"))
        vm.onSend()
        runCurrent()

        assertTrue(h.sent("terminal.keys").isEmpty())
        assertEquals("1", h.sent("terminal.send").single().params().getValue("text").jsonPrimitive.content)
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

    /** Ctrl armed, then a typed character that is not a letter: it is inserted, nothing is sent, and Ctrl stays armed until Send. */
    @Test
    fun ctrlThenATypedDigitIsInsertedAndStaysArmed() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onInputChange(TextFieldValue("1"))

        assertEquals("1", vm.state.value.input.text)
        assertTrue(vm.state.value.ctrlArmed)
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

    /**
     * An agent whose machine drops keeps the status and the space it had while connected (phase 7 r7). Its label says offline:
     * the core never reported it finished, so the screen must not say "finished" and must not say "No space" for it.
     */
    @Test
    fun anOfflineAgentKeepsItsStatusAndSpaceAndSaysOffline() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(listOf(spaceJson("sp_a", "web", "/home/u/web")))
        val terminal = terminalJson(
            "t1",
            status = "working",
            labels = mapOf("whistl.space" to "sp_a"),
            cwd = "/home/u/web",
        )
        h.core.terminals += terminal
        h.core.screens["t1"] = "compiling"
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, listOf(terminal))) }
        h.addMachine()
        runCurrent()
        val vm = AgentViewModel(h.terminals, h.agents, h.machines, DefaultSettingsRepository(InMemorySettingsStore()), "m1", "t1")
        runCurrent()
        assertEquals(AgentStatusLabel.Working, vm.state.value.statusLabel)
        assertEquals("web", vm.state.value.spaceName)

        h.connector.lastSession!!.kill()
        advanceTimeBy(500)
        runCurrent()

        val state = vm.state.value
        assertEquals(TerminalReach.Offline, state.reach)
        assertEquals(AgentStatusLabel.Offline, state.statusLabel)
        assertEquals("web", state.spaceName)
        assertEquals(AgentStatus.Working, state.status)
        assertEquals("t1", state.address)
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

    /** Every key pressed reaches the terminal as it is pressed, like a keyboard on a computer. */
    @Test
    fun everyKeyReachesTheTerminalImmediately() = runTest {
        val (h, vm, _) = openAgent()

        vm.onType("l")
        vm.onType("s")
        runCurrent()

        val pressed = h.sent("terminal.keys").map { message ->
            message.params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf(listOf("l"), listOf("s")), pressed)
        // A key is a key: nothing may travel as text, which the core would submit with Enter.
        assertTrue("a key must never be sent as text", h.sent("terminal.send").isEmpty())
    }

    /** Enter is the carriage return, sent after whatever was typed before it. */
    @Test
    fun enterIsACarriageReturn() = runTest {
        val (h, vm, _) = openAgent()

        vm.onType("ls")
        vm.onType("\r")
        runCurrent()

        val pressed = h.sent("terminal.keys").map { message ->
            message.params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf(listOf("l", "s"), listOf("enter")), pressed)
        assertTrue("Enter is a key, not a line end in text", h.sent("terminal.send").isEmpty())
    }

    /** Send as keys: the line goes as characters, with no Return, and the field empties (0.6.1). */
    @Test
    fun sendAsKeysSendsTheLineWithoutReturn() = runTest {
        val (h, vm, _) = openAgent()

        vm.onInputChange(TextFieldValue("m"))
        vm.onSendKeys()
        runCurrent()

        val pressed = h.sent("terminal.keys").single().keyNames()
        assertEquals(listOf("m"), pressed)
        assertTrue("no line is submitted", h.sent("terminal.send").isEmpty())
        assertEquals("", vm.state.value.input.text)
    }

    /** Ctrl latched on the bar applies to the next character typed on the phone's keyboard: ^C, not `c`. */
    @Test
    fun ctrlAppliesToATypedCharacter() = runTest {
        val (h, vm, _) = openAgent()

        vm.onKey(ShortcutKey.CTRL)
        vm.onType("c")
        runCurrent()

        assertEquals(listOf("ctrl+c"), h.sent("terminal.keys").single().keyNames())
        assertFalse("Ctrl is spent on the key it combined with", vm.state.value.ctrlArmed)
    }

    /** Backspace is DELETE, straight to the terminal: its own line editor owns the line. */
    @Test
    fun backspaceIsDelete() = runTest {
        val (h, vm, _) = openAgent()

        vm.onBackspace(2)
        runCurrent()

        val pressed = h.sent("terminal.keys").single().params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("backspace", "backspace"), pressed)
    }


    /** A blocked agent takes the keystroke forced, the same way Send delivered text. */
    @Test
    fun aBlockedAgentTakesKeystrokesForced() = runTest {
        val (h, vm, _) = openAgent(status = "blocked")

        vm.onType("y")
        runCurrent()

        // A blocked agent takes the key at once: `terminal.keys` does not wait for the program to be idle, and unlike
        // `terminal.send` it does not submit the line either.
        assertEquals(listOf("y"), h.sent("terminal.keys").single().keyNames())
    }

    /** A deletion travels as DELETE, one per erased character: the terminal owns the line, not the phone. */
    @Test
    fun aDeletionTravelsAsDelete() = runTest {
        val (h, vm, _) = openAgent()

        vm.onBackspace(2)
        runCurrent()

        val pressed = h.sent("terminal.keys").single().params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("backspace", "backspace"), pressed)
    }

    /** Pasting sends the clipboard's text as it is, at the cursor. */
    @Test
    fun pasteSendsTheClipboardTextAtTheCursor() = runTest {
        val (h, vm, _) = openAgent()

        vm.onPaste("git status")
        runCurrent()

        val pressed = h.sent("terminal.keys").single().params().getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        assertEquals("git status".map { if (it == ' ') "space" else it.toString() }, pressed)
        assertTrue("a paste must not submit the line", h.sent("terminal.send").isEmpty())
    }

    /** Nothing is sent while the terminal cannot take input. */
    @Test
    fun typingIsIgnoredWhileTheTerminalCannotTakeIt() = runTest {
        val (h, vm, _) = openAgent(state = "finished")

        vm.onType("l")
        vm.onBackspace(1)
        vm.onPaste("x")
        runCurrent()

        assertTrue(h.sent("terminal.send").isEmpty())
    }

}
