package io.github.werlang.whistl.ui.machines

import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticInstalled
import io.github.werlang.whistl.support.testHello
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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
 * The Machines view model (SPEC §2.1, phase 4b r1): one card per machine and state, the card menu, the removal confirmation,
 * the host-key prompt a card opens, and the removal's effect on the spaces list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachinesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /**
     * Asserts that a value is present, and returns it.
     *
     * @param value the value that must not be null.
     * @return the value.
     */
    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /**
     * A machine with the given id and address.
     *
     * @param id the id.
     * @param host the host.
     * @param port the port.
     * @param user the user name.
     * @return the machine.
     */
    private fun machine(id: String, host: String = "$id.lan", port: Int = 22, user: String = "ci"): Machine =
        Machine(id = id, name = id, host = host, port = port, user = user)

    /**
     * The view model over the given repositories, with no agents and no installed list unless given.
     *
     * @param machines the machines.
     * @param agents the agents.
     * @param installed the installed agents.
     * @return the view model.
     */
    private fun model(
        machines: MachinesRepository,
        agents: AgentsRepository = StaticAgents(),
        installed: InstalledAgentsRepository = StaticInstalled(),
    ): MachinesViewModel = MachinesViewModel(machines, agents, installed)

    /** With no machines, the screen has no cards and no sheet. */
    @Test
    fun anEmptyListHasNoCardsAndNoSheet() {
        val vm = model(ScriptedMachines())

        val state = vm.state.value
        assertTrue(state.cards.isEmpty())
        assertNull(state.menu)
        assertNull(state.hostKey)
        assertNull(state.addSession)
    }

    /** Each connection state gets its own card, with the status and, for an error, the reason the user acts on. */
    @Test
    fun eachConnectionStateShowsItsStatusAndIssue() {
        val cases = listOf(
            ConnectionState.Connected(testHello()) to (MachineStatus.Connected to null),
            ConnectionState.Connecting to (MachineStatus.Connecting to null),
            ConnectionState.Offline to (MachineStatus.Offline to null),
            ConnectionState.Error(ErrorKind.AuthFailed) to (MachineStatus.Error to MachineIssue.LoginRefused),
            ConnectionState.Error(ErrorKind.UnsupportedGeneration) to (MachineStatus.Error to MachineIssue.UnsupportedCore),
            ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new") to (MachineStatus.Error to MachineIssue.HostKeyToConfirm),
            ConnectionState.HostKeyChanged("ssh-ed25519", "SHA256:old", "SHA256:new") to
                (MachineStatus.Error to MachineIssue.HostKeyChanged),
        )
        cases.forEach { (connection, expected) ->
            val machines = ScriptedMachines()
            machines.put(machine("m1"), connection)

            val card = model(machines).state.value.cards.single()

            assertEquals("status for $connection", expected.first, card.status)
            assertEquals("issue for $connection", expected.second, card.issue)
        }
    }

    /** A connected card shows the version; an offline one does not show a count or a version. */
    @Test
    fun aConnectedCardShowsItsVersionAndAnOfflineOneDoesNot() {
        val machines = ScriptedMachines()
        machines.put(machine("up"), ConnectionState.Connected(testHello()))
        machines.put(machine("down"), ConnectionState.Offline)

        val cards = model(machines).state.value.cards.associateBy { it.id }

        assertEquals("0.1.18", cards.getValue("up").version)
        assertEquals(0, cards.getValue("up").agentCount)
        assertNull(cards.getValue("down").version)
        assertNull(cards.getValue("down").agentCount)
    }

    /** The address shows the port only when it is not 22, and an IPv6 host in brackets. */
    @Test
    fun theAddressCarriesItsPortAndHostAndTheTextFormatsThem() {
        val machines = ScriptedMachines()
        machines.put(machine("std", host = "build.lan", port = 22, user = "ci"), ConnectionState.Offline)
        machines.put(machine("alt", host = "build.lan", port = 2222, user = "ci"), ConnectionState.Offline)
        machines.put(machine("v6", host = "fe80::1", port = 22, user = "ci"), ConnectionState.Offline)

        val cards = model(machines).state.value.cards.associateBy { it.id }

        assertEquals(MachineAddress("ci", "build.lan", 22), cards.getValue("std").address)
        assertEquals(MachineAddress("ci", "build.lan", 2222), cards.getValue("alt").address)
        assertEquals(MachineAddress("ci", "fe80::1", 22), cards.getValue("v6").address)
    }

    /** The card menu opens for its machine, and Reconnect is off while the machine is connecting. */
    @Test
    fun theMenuOpensForItsMachineAndReconnectIsOffWhileConnecting() {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Connecting)
        val vm = model(machines)

        vm.openMenu("m1")

        val menu = present(vm.state.value.menu)
        assertEquals("m1", menu.id)
        assertFalse(menu.reconnectEnabled)
        assertFalse(vm.state.value.cards.single().reconnectEnabled)

        vm.closeMenu()
        assertNull(vm.state.value.menu)
    }

    /** Reconnect restarts the machine's attempt and closes the menu. */
    @Test
    fun reconnectRestartsTheAttemptAndClosesTheMenu() = runTest {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        val vm = model(machines)
        vm.openMenu("m1")

        vm.reconnect("m1")
        runCurrent()

        assertEquals(listOf("m1"), machines.reconnected)
        assertNull(vm.state.value.menu)
    }

    /** Remove asks first: the menu closes, the confirmation opens, and cancelling keeps the machine. */
    @Test
    fun removeAsksFirstAndCancelKeepsTheMachine() {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        val vm = model(machines)
        vm.openMenu("m1")

        vm.askRemove("m1")
        assertNull(vm.state.value.menu)
        assertEquals("m1", vm.state.value.confirmRemove?.id)

        vm.cancelRemove()
        assertNull(vm.state.value.confirmRemove)
        assertEquals(listOf("m1"), vm.state.value.cards.map { it.id })
        assertTrue(machines.removed.isEmpty())
    }

    /** Confirming the removal removes the machine from the repository and from the cards. */
    @Test
    fun confirmingRemovalRemovesTheMachine() = runTest {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        machines.put(machine("m2"), ConnectionState.Offline)
        val vm = model(machines)

        vm.askRemove("m1")
        vm.confirmRemove()
        runCurrent()

        assertEquals(listOf("m1"), machines.removed)
        assertEquals(listOf("m2"), vm.state.value.cards.map { it.id })
        assertNull(vm.state.value.confirmRemove)
    }

    /**
     * Removing a machine takes its spaces out of the spaces list. The real repositories are used here: the spaces list is the
     * one the Spaces screen reads.
     */
    @Test
    fun removingAMachineTakesItsSpacesOutOfTheSpacesList() = runTest {
        val stack = RepoStack(this)
        val id = stack.addMachine()
        runCurrent()
        val vm = MachinesViewModel(stack.machines, stack.agents, StaticInstalled())
        runCurrent()
        assertTrue("the machine is listed before removal", stack.spaces.groups().value.any { it.machineId == id })

        vm.askRemove(id)
        vm.confirmRemove()
        runCurrent()

        assertFalse(stack.machines.machines().value.any { it.id == id })
        assertFalse(stack.spaces.groups().value.any { it.machineId == id })
        assertTrue(vm.state.value.cards.isEmpty())
    }

    /** A machine with an unknown host key opens the host-key sheet; trusting it connects. */
    @Test
    fun anUnknownHostKeyOpensTheSheetAndTrustConnects() = runTest {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
        val vm = model(machines)

        val prompt = present(vm.state.value.hostKey) as HostKeyPrompt.Unknown
        assertEquals("ssh-ed25519", prompt.algorithm)
        assertEquals("SHA256:new", prompt.fingerprint)

        vm.trust("m1")
        runCurrent()

        assertEquals(listOf("m1"), machines.trusted)
        assertNull(vm.state.value.hostKey)
        assertEquals(MachineStatus.Connected, vm.state.value.cards.single().status)
    }

    /** A changed host key opens the blocking sheet, and trusting it does nothing: the repository is not asked. */
    @Test
    fun aChangedHostKeyOpensTheBlockingSheetAndCannotBeTrusted() = runTest {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.HostKeyChanged("ssh-ed25519", "SHA256:old", "SHA256:new"))
        val vm = model(machines)

        val prompt = present(vm.state.value.hostKey) as HostKeyPrompt.Changed
        assertEquals("SHA256:old", prompt.expected)
        assertEquals("SHA256:new", prompt.actual)

        vm.trust("m1")
        runCurrent()

        assertTrue("a changed key is never trusted", machines.trusted.isEmpty())
        present(vm.state.value.hostKey)
    }

    /** Closing the sheet keeps it closed for the same state, and it returns when the machine's state changes. */
    @Test
    fun dismissingTheSheetHoldsUntilTheStateChanges() {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
        val vm = model(machines)
        vm.dismissHostKey(present(vm.state.value.hostKey))
        assertNull(vm.state.value.hostKey)

        machines.setState("m1", ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
        assertNull("an equal state does not reopen the sheet", vm.state.value.hostKey)

        machines.setState("m1", ConnectionState.Offline)
        machines.setState("m1", ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
        assertNotNull("a new host-key event reopens the sheet", vm.state.value.hostKey)
    }

    /** The host-key sheet is held back while a menu or a confirmation is open, and comes back after. */
    @Test
    fun theHostKeySheetWaitsWhileAMenuIsOpen() {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new"))
        val vm = model(machines)

        vm.openMenu("m1")
        assertNull(vm.state.value.hostKey)

        vm.closeMenu()
        present(vm.state.value.hostKey)
    }

    /** The Add machine session opens with a new session number each time, and closes. */
    @Test
    fun theAddSessionOpensWithANewNumberEachTime() {
        val vm = model(ScriptedMachines())

        vm.openAdd()
        val first = present(vm.state.value.addSession)
        vm.closeAdd()
        assertNull(vm.state.value.addSession)
        vm.openAdd()

        assertTrue(present(vm.state.value.addSession) > first)
    }

    /** The install-detection state flows into the card as the installed agents, by display name. */
    @Test
    fun theInstalledAgentsOfAMachineShowOnItsCard() {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        val vm = model(machines, installed = StaticInstalled(mapOf("m1" to listOf(io.github.werlang.whistl.domain.KnownAgents.table[0]))))

        assertEquals(listOf(io.github.werlang.whistl.domain.KnownAgents.table[0].displayName), vm.state.value.cards.single().installed)
    }

    /** Advancing time changes nothing about the cards: the view model is a function of its inputs. */
    @Test
    fun theCardsDoNotChangeWithTimeAlone() = runTest {
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        val vm = model(machines)
        val before = vm.state.value.cards

        advanceTimeBy(120_000L)
        runCurrent()

        assertEquals(before, vm.state.value.cards)
    }

    /**
     * Phase 6 P0-2, call site `observe` in the Machines view model's trust: a machine that is no longer added shows the problem
     * on the screen, and nothing crashes.
     */
    @Test
    fun trustOfAMachineThatIsNotThereShowsMissing() = runTest {
        val vm = model(ScriptedMachines())

        vm.trust("no-such-machine")
        runCurrent()

        assertEquals(MachinesProblem.MachineMissing, vm.state.value.problem)
    }

    /**
     * Phase 6 P0-2, call site `reconnect` in the Machines view model: a reconnect for a machine that is no longer added shows the
     * problem on the screen, and nothing crashes.
     */
    @Test
    fun reconnectOfAMachineThatIsNotThereShowsMissing() = runTest {
        val vm = model(ScriptedMachines())

        vm.reconnect("no-such-machine")
        runCurrent()

        assertEquals(MachinesProblem.MachineMissing, vm.state.value.problem)
    }
}
