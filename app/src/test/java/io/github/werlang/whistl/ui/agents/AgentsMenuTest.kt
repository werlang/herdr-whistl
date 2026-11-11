package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.data.ActionResult
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.AgentAction
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.support.ScriptedMachines
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The action sheet of the Agents list (phase 9, SPEC §2.4): what the four actions send, what the list says afterwards,
 * and that a rename never sends an empty name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsMenuTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val machine = Machine(id = MACHINE, name = "rpi5", host = "rpi5.lan", port = 22, user = "ci")

    private fun terminal(address: String = ADDRESS, labels: Map<String, String> = emptyMap(), name: String? = "claude") =
        Terminal(
            address = address,
            name = name,
            argv = listOf("/usr/bin/claude"),
            cwd = "/home/pablo/web",
            labels = labels,
            state = TerminalState.Live,
            status = AgentStatus.Working,
            turn = 0,
            exitCode = null,
            createdMs = 0,
            finishedMs = null,
            title = null,
            pid = 1,
            signal = null,
            reason = null,
            keep = true,
        )

    private suspend fun frame(agents: StaticAgents, terminals: List<Terminal> = listOf(terminal())): Pair<AgentsViewModel, MachineAgent> {
        val machines = ScriptedMachines().apply { put(machine, ConnectionState.Offline) }
        agents.agents.value = listOf(MachineAgent(MACHINE, Agent(terminals.first()), null))
        val vm = AgentsViewModel(agents, machines, FakePins())
        return vm to agents.agents.value.first()
    }

    /** The sheet knows which row it acts on, and closes without acting. */
    @Test
    fun openingAndClosingTheSheetDoesNotAct() = runTest {
        val agents = StaticAgents()
        val (vm, _) = frame(agents)
        val row = vm.state.value.rows.first()

        vm.openMenu(row)
        assertEquals(row, vm.state.value.menu?.row)

        vm.closeMenu()
        assertNull(vm.state.value.menu)
        assertNull("nothing was sent", agents.acted)
    }

    /** Interrupt, stop and close each send their own action for the sheet's row, and the list says what happened. */
    @Test
    fun eachActionSendsItselfAndIsReported() = runTest {
        val cases = listOf(
            Triple<(AgentsViewModel) -> Unit, AgentAction, AgentNotice>(
                { it.interrupt() }, AgentAction.Interrupt, AgentNotice.Interrupted,
            ),
            Triple<(AgentsViewModel) -> Unit, AgentAction, AgentNotice>(
                { it.close() }, AgentAction.Close, AgentNotice.Closed,
            ),
        )
        cases.forEach { (call, action, notice) ->
            val agents = StaticAgents()
            val (vm, _) = frame(agents)
            vm.openMenu(vm.state.value.rows.first())

            call(vm)

            assertEquals(action, agents.acted?.second)
            assertEquals(ADDRESS, agents.acted?.first)
            assertEquals(notice, vm.state.value.notice)
            assertNull("the sheet closes after acting", vm.state.value.menu)
        }
    }

    /** A rename sends the trimmed name as `whistl.name`. */
    @Test
    fun renameSendsTheTrimmedName() = runTest {
        val agents = StaticAgents()
        val (vm, _) = frame(agents)
        vm.openMenu(vm.state.value.rows.first())

        vm.startRename()
        assertEquals("the field starts with the current name", "claude", vm.state.value.menu?.draft)

        vm.renameDraft("  deploy  ")
        vm.confirmRename()

        assertEquals(AgentAction.Rename("deploy"), agents.acted?.second)
        assertEquals(AgentNotice.Renamed, vm.state.value.notice)
    }

    /** An empty name is refused by the list, not sent. */
    @Test
    fun anEmptyRenameIsNotSent() = runTest {
        val agents = StaticAgents()
        val (vm, _) = frame(agents)
        vm.openMenu(vm.state.value.rows.first())
        vm.startRename()

        vm.renameDraft("   ")
        vm.confirmRename()

        assertNull(agents.acted)
        assertEquals("the sheet stays open for another try", true, vm.state.value.menu?.renaming)
    }

    /** A machine that is not connected, an agent that is gone and a refusal each have their own message. */
    @Test
    fun failuresAreReportedAsThemselves() = runTest {
        val cases = listOf(
            ActionResult.Offline to AgentNotice.Offline,
            ActionResult.Gone to AgentNotice.Gone,
            ActionResult.Failed(AgentAction.Interrupt) to AgentNotice.Failed,
        )
        cases.forEach { (result, notice) ->
            val agents = StaticAgents().apply { this.result = result }
            val (vm, _) = frame(agents)
            vm.openMenu(vm.state.value.rows.first())

            vm.interrupt()

            assertEquals(notice, vm.state.value.notice)
        }
    }

    /** A label set by a rename wins over the name the terminal was started with. */
    @Test
    fun aRenamedAgentShowsItsNewName() = runTest {
        val agents = StaticAgents()
        val named = terminal(labels = mapOf(Agent.NAME_LABEL to "deploy"))
        agents.agents.value = listOf(MachineAgent(MACHINE, Agent(named), null))
        val vm = AgentsViewModel(agents, ScriptedMachines().apply { put(machine, ConnectionState.Offline) }, FakePins())

        assertEquals("deploy", vm.state.value.rows.single().name)
    }

    private companion object {
        const val MACHINE = "rpi5"
        const val ADDRESS = "t15s"
    }
}
