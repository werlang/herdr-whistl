package io.github.werlang.whistl.ui.machines

import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.data.MachineField
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.support.FixedClientKey
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RecordingClipboard
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.testHello
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Add machine sheet (SPEC §2.1, phase 4b r1 §2.5, §2.6): the form's rules and messages, the password that never becomes a
 * String in the state, the host-key stages, a refused login with Retry, an unreachable machine, and the public key copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMachineViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val machines = ScriptedMachines()
    private val clipboard = RecordingClipboard()

    /** A view model over the scripted repository, with the fixed public key and the recording clipboard. */
    private fun vm(): AddMachineViewModel = AddMachineViewModel(machines, FixedClientKey, clipboard)

    /**
     * Fills the host and the user, the minimum for Connect.
     *
     * @param vm the view model.
     * @param host the host text.
     * @param user the user text.
     */
    private fun fill(vm: AddMachineViewModel, host: String = "build.lan", user: String = "ci") {
        vm.onHost(host)
        vm.onUser(user)
    }

    /** Connect is off while the host or the user is empty, and on once both are filled. */
    @Test
    fun connectIsOffUntilHostAndUserAreFilled() {
        val vm = vm()
        assertFalse(vm.uiState.value.canConnect)

        vm.onHost("build.lan")
        assertFalse(vm.uiState.value.canConnect)

        vm.onUser("ci")
        assertTrue(vm.uiState.value.canConnect)

        vm.onHost("")
        assertFalse(vm.uiState.value.canConnect)
    }

    /** A blank port is the default 22. */
    @Test
    fun aBlankPortIsTheDefaultTwentyTwo() {
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals(22, machines.added.single().port)
        assertTrue(vm.uiState.value.problems.isEmpty())
    }

    /** Port 0, 65536 and a port that is not a number each get a typed message under the port field. */
    @Test
    fun portsOutsideTheRangeAndNonNumbersAreRejectedUnderThePort() {
        listOf(
            "0" to FieldProblem.PortOutOfRange,
            "65536" to FieldProblem.PortOutOfRange,
            "abc" to FieldProblem.PortNotANumber,
        ).forEach { (text, expected) ->
            val vm = vm()
            fill(vm)
            vm.onPort(text)

            vm.connect(null)

            assertEquals("port $text", expected, vm.uiState.value.problems[AddField.Port])
        }
        assertTrue("no machine was added", machines.added.isEmpty())
    }

    /** A port inside the range is kept. */
    @Test
    fun aPortInsideTheRangeIsKept() {
        val vm = vm()
        fill(vm)
        vm.onPort("2222")

        vm.connect(null)

        assertEquals(2222, machines.added.single().port)
    }

    /** Host rules: empty, bad characters; an IPv6 address is accepted. */
    @Test
    fun hostRulesGiveTypedMessagesAndAcceptIPv6() {
        val empty = vm()
        fill(empty, host = "")
        empty.connect(null)
        assertEquals(FieldProblem.HostEmpty, empty.uiState.value.problems[AddField.Host])

        val bad = vm()
        fill(bad, host = "bad host!")
        bad.connect(null)
        assertEquals(FieldProblem.HostBadCharacters, bad.uiState.value.problems[AddField.Host])

        val v6 = vm()
        fill(v6, host = "2001:db8::1")
        v6.connect(null)
        assertTrue(v6.uiState.value.problems.isEmpty())
        assertEquals("2001:db8::1", machines.added.last().host)
    }

    /** User rules: empty, and characters outside the allowed set. */
    @Test
    fun userRulesGiveTypedMessages() {
        val empty = vm()
        fill(empty, user = "")
        empty.connect(null)
        assertEquals(FieldProblem.UserEmpty, empty.uiState.value.problems[AddField.User])

        val bad = vm()
        fill(bad, user = "Bad User!")
        bad.connect(null)
        assertEquals(FieldProblem.UserBadCharacters, bad.uiState.value.problems[AddField.User])
    }

    /** The default name is the first label of a host name, or the whole literal of an address. */
    @Test
    fun theDefaultNameIsTheFirstLabelOrTheAddressLiteral() {
        val name = vm()
        fill(name, host = "build.lan")
        assertEquals("build", name.uiState.value.defaultName)
        name.connect(null)
        assertEquals("build", machines.added.single().name)

        val literal = vm()
        fill(literal, host = "192.168.0.10")
        assertEquals("192.168.0.10", literal.uiState.value.defaultName)
        literal.connect(null)
        assertEquals("192.168.0.10", machines.added.last().name)
    }

    /** A typed name is kept, even when the host has a default. */
    @Test
    fun aTypedNameWinsOverTheDefault() {
        val vm = vm()
        fill(vm, host = "build.lan")
        vm.onName("Build box")

        vm.connect(null)

        assertEquals("Build box", machines.added.single().name)
    }

    /** Key mode: no password is handed over, and the public key is the one line the device has. */
    @Test
    fun keyModeHandsOverNoPasswordAndShowsThePublicKey() {
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals(listOf<CharArray?>(null), machines.passwords)
        assertEquals(FixedClientKey.LINE, vm.uiState.value.publicKey)
    }

    /** Password mode: the password is required, and an empty one is reported under the password field. */
    @Test
    fun passwordModeReportsAnEmptyPasswordUnderTheField() {
        val vm = vm()
        fill(vm)
        vm.onAuth(AuthChoice.Password)

        vm.connect("".toCharArray())

        assertEquals(FieldProblem.PasswordEmpty, vm.uiState.value.problems[AddField.Password])
        assertTrue("no machine was added", machines.added.isEmpty())
    }

    /** The password is handed over as a CharArray, and the state never holds its text. */
    @Test
    fun thePasswordIsPassedAsCharsAndIsNeverInTheState() {
        val vm = vm()
        fill(vm)
        vm.onAuth(AuthChoice.Password)

        vm.connect("hunter2-secret".toCharArray())

        assertEquals("hunter2-secret", machines.passwords.single()?.concatToString())
        assertFalse(
            "the state's text holds no password",
            vm.uiState.value.toString().contains("hunter2-secret"),
        )
    }

    /** The public key copy goes through the clipboard seam, and the sheet notes that it was copied. */
    @Test
    fun copyingThePublicKeyUsesTheClipboard() {
        val vm = vm()

        vm.copyPublicKey()

        assertEquals(listOf(FixedClientKey.LINE), clipboard.copied)
        assertTrue(vm.uiState.value.publicKeyCopied)
    }

    /** An unknown host key: the sheet asks to trust it, and trusting connects and closes the sheet. */
    @Test
    fun unknownHostKeyAsksThenTrustConnectsAndCloses() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-unknown", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "unknown.demo")

        vm.connect(null)

        assertEquals(AddStage.HostKeyUnknown, vm.uiState.value.stage)
        assertEquals("SHA256:new", vm.uiState.value.hostKeyFingerprint)
        assertEquals("ssh-ed25519", vm.uiState.value.hostKeyAlgorithm)

        vm.trust()

        assertEquals(listOf("s-unknown"), machines.trusted)
        assertEquals(AddStage.Connected, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.closed)
    }

    /** A changed host key: the sheet blocks, and no trust is possible from it. */
    @Test
    fun changedHostKeyBlocksAndHasNoTrust() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-changed", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.HostKeyChanged("ssh-ed25519", "SHA256:old", "SHA256:new"))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "changed.demo")

        vm.connect(null)

        assertEquals(AddStage.HostKeyChanged, vm.uiState.value.stage)
        assertEquals("SHA256:old", vm.uiState.value.trustedFingerprint)
        assertEquals("SHA256:new", vm.uiState.value.presentedFingerprint)

        vm.trust()

        assertTrue("a changed key is never trusted", machines.trusted.isEmpty())
        assertEquals(AddStage.HostKeyChanged, vm.uiState.value.stage)
    }

    /** A changed key can be removed from the sheet: the machine is forgotten and the sheet closes. */
    @Test
    fun removingFromTheChangedKeySheetForgetsTheMachine() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-gone", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.HostKeyChanged("ssh-ed25519", "SHA256:old", "SHA256:new"))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "changed.demo")
        vm.connect(null)

        vm.removeMachine()

        assertEquals(listOf("s-gone"), machines.removed)
        assertTrue(vm.uiState.value.closed)
    }

    /** A refused login shows the reason; Retry tries the connection once more. */
    @Test
    fun aRefusedLoginShowsTheReasonAndRetryTriesAgain() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-auth", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.Error(ErrorKind.AuthFailed))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "auth.demo")

        vm.connect(null)

        assertEquals(AddStage.LoginRefused, vm.uiState.value.stage)

        vm.retry()

        assertEquals(listOf("s-auth"), machines.reconnected)
    }

    /** An unreachable machine: the attempt starts, then goes offline, and the sheet shows Unreachable with Retry. */
    @Test
    fun anUnreachableMachineShowsUnreachableAfterItsAttempt() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-far", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.Connecting)
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "unreach.demo")

        vm.connect(null)
        assertEquals(AddStage.Connecting, vm.uiState.value.stage)

        machines.setState("s-far", ConnectionState.Offline)

        assertEquals(AddStage.Unreachable, vm.uiState.value.stage)
    }

    /** A machine that is offline before its first attempt is still connecting, not unreachable. */
    @Test
    fun anOfflineStateBeforeTheFirstAttemptIsStillConnecting() = runTest {
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals("the new machine starts offline", AddStage.Connecting, vm.uiState.value.stage)
    }

    /** A rejected field from the repository shows under that field. */
    @Test
    fun aRepositoryRejectionShowsUnderItsField() = runTest {
        machines.addAnswer = { AddResult.Rejected(MachineField.Host, Rejection.BAD_FORMAT) }
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals(FieldProblem.HostBadFormat, vm.uiState.value.problems[AddField.Host])
        assertEquals(AddStage.Form, vm.uiState.value.stage)
    }

    /** Connecting a machine that connects straight away closes the sheet. */
    @Test
    fun aConnectedMachineClosesTheSheet() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-ok", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.Connected(testHello()))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals(AddStage.Connected, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.closed)
        assertNotNull(vm.uiState.value.machineId)
    }

    /** Closing the sheet keeps the machine in the list: it only marks the sheet closed. */
    @Test
    fun closingTheSheetKeepsTheMachine() = runTest {
        val vm = vm()
        fill(vm)
        vm.connect(null)

        vm.close()

        assertTrue(vm.uiState.value.closed)
        assertTrue(machines.removed.isEmpty())
        assertNull(machines.removed.firstOrNull())
    }

    /**
     * Phase 6 P0-2, call site `observe` in the Add machine view model: a machine the add returned but that is not stored shows the
     * sheet's Missing stage, and nothing crashes.
     */
    @Test
    fun followingAMachineThatIsNotThereShowsMissing() = runTest {
        machines.addAnswer = { draft -> AddResult.Added(Machine("ghost", draft.name, draft.host, draft.port, draft.user)) }
        val vm = vm()
        fill(vm)

        vm.connect(null)

        assertEquals(AddStage.Missing, vm.uiState.value.stage)
    }

    /**
     * Phase 6 P0-2, call site `reconnect` in the Add machine view model: Retry after the machine was removed shows the Missing
     * stage.
     */
    @Test
    fun retryAfterTheMachineWasRemovedShowsMissing() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-gone-later", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.Error(ErrorKind.AuthFailed))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "auth.demo")
        vm.connect(null)
        assertEquals(AddStage.LoginRefused, vm.uiState.value.stage)
        machines.remove("s-gone-later")

        vm.retry()
        runCurrent()

        assertEquals(AddStage.Missing, vm.uiState.value.stage)
    }

    /**
     * Phase 6 P0-2, call site `confirmHostKey` in the Add machine view model: Trust after the machine was removed shows the Missing
     * stage.
     */
    @Test
    fun trustAfterTheMachineWasRemovedShowsMissing() = runTest {
        machines.addAnswer = { draft ->
            val machine = Machine("s-key-gone", draft.name, draft.host, draft.port, draft.user)
            machines.put(machine, ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
            AddResult.Added(machine)
        }
        val vm = vm()
        fill(vm, host = "unknown.demo")
        vm.connect(null)
        assertEquals(AddStage.HostKeyUnknown, vm.uiState.value.stage)
        machines.remove("s-key-gone")

        vm.trust()
        runCurrent()

        assertEquals(AddStage.Missing, vm.uiState.value.stage)
    }
}
