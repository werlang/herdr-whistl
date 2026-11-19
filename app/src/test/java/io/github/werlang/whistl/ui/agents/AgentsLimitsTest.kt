package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.LimitsResult
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticInstalled
import io.github.werlang.whistl.support.StaticSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The limits, asked from an agent's action sheet (0.7.5): the user taps, the machine is asked once, and the answer lands in the
 * sheet that is open — including the reason when the machine could not answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsLimitsTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** The action reaches the agent's own machine, and the answer reaches the sheet that is open. */
    @Test
    fun checkingTheLimitsAsksTheAgentsOwnMachine() = runTest {
        val limits = FakeLimits()
        val vm = model(limits)

        vm.openMenu(vm.state.value.rows.first())
        vm.showLimits()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(MACHINE_ID), limits.asked)
        assertTrue("the answer reached the sheet", vm.state.value.menu?.limits is LimitsResult.Done)
        assertEquals(false, vm.state.value.menu?.limitsLoading)
    }

    /** Opening the sheet alone costs nothing: three providers are not asked to rename an agent. */
    @Test
    fun openingTheMenuDoesNotAskAnything() = runTest {
        val limits = FakeLimits()
        val vm = model(limits)

        vm.openMenu(vm.state.value.rows.first())
        testScheduler.advanceUntilIdle()

        assertTrue(limits.asked.isEmpty())
        assertNull(vm.state.value.menu?.limits)
    }

    /** A machine that cannot answer keeps its reason in the same sheet. */
    @Test
    fun anOfflineMachineKeepsItsReasonInTheSheet() = runTest {
        val limits = FakeLimits(answer = mapOf(MACHINE_ID to LimitsResult.Offline))
        val vm = model(limits)

        vm.openMenu(vm.state.value.rows.first())
        vm.showLimits()
        testScheduler.advanceUntilIdle()

        assertEquals(LimitsResult.Offline, vm.state.value.menu?.limits)
    }

    /** A second tap while the command runs does not start a second one. */
    @Test
    fun aSecondTapWhileAskingDoesNothing() = runTest {
        val limits = FakeLimits()
        val vm = model(limits)

        vm.openMenu(vm.state.value.rows.first())
        vm.showLimits()
        vm.showLimits()
        testScheduler.advanceUntilIdle()

        assertEquals(1, limits.asked.size)
    }

    /** A machine with one live, working agent in a space, and the view model wired to [limits]. */
    private suspend fun TestScope.model(limits: FakeLimits): AgentsViewModel {
        val agents = StaticAgents()
        val machines = ScriptedMachines().apply { put(machine, ConnectionState.Offline) }
        agents.agents.value = listOf(MachineAgent(MACHINE_ID, Agent(terminal()), SPACE))
        return AgentsViewModel(agents, machines, FakePins(), StaticSpaces(emptyList()), StaticInstalled(), limits = limits)
    }

    private fun terminal() = Terminal(
        address = "t1",
        name = "claude",
        argv = listOf("claude"),
        cwd = SPACE.folder,
        labels = mapOf("whistl.space" to SPACE.id),
        state = TerminalState.Live,
        status = AgentStatus.Working,
        turn = 1,
        exitCode = null,
        createdMs = 0,
        finishedMs = null,
        title = "claude",
        pid = 1,
        signal = null,
        reason = null,
        keep = true,
    )

    private companion object {
        const val MACHINE_ID = "mac"
        val machine = Machine(id = MACHINE_ID, name = "mac", host = "mac.local", port = 22, user = "demo")
        val SPACE = SpaceRecord(id = "sp1", name = "web", folder = "/tmp", pinned = false, order = 0)
    }
}
