package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Agents list over the real agents repository, on the fake core's events snapshot (phase 4 r2, §12). */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** A stack with one connected machine (`mac`) and [terminals] in its events snapshot. */
    private suspend fun TestScope.listing(vararg terminals: JsonObject): Pair<RepoStack, AgentsViewModel> {
        val h = RepoStack(this)
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, terminals.toList())) }
        h.addMachine()
        runCurrent()
        val vm = AgentsViewModel(h.agents, h.machines, FakePins())
        runCurrent()
        return h to vm
    }

    /** With no agents, every chip counts zero, there are no rows, and the badge is hidden. */
    @Test
    fun anEmptyListHasNoRowsAndZeroCounts() = runTest {
        val (_, vm) = listing()

        val state = vm.state.value
        assertEquals(listOf(0, 0, 0, 0, 0), state.chips.map { it.count })
        assertTrue(state.rows.isEmpty())
        assertEquals(0, state.blockedCount)
    }

    /** A terminal no space claims shows up only under its own chip: the list of agents is the spaces' (0.6.3). */
    @Test
    fun aTerminalNoSpaceClaimsIsOnlyUnderTheNoSpaceChip() = runTest {
        val (_, vm) = listing(
            terminalJson("t-bound"),
            terminalJson("t-bare", labels = emptyMap()),
        )

        val all = vm.state.value
        assertEquals("All shows the spaces' agents", listOf("t-bound"), all.rows.map { it.address })
        assertEquals(1, all.chips.first { it.filter == AgentsFilter.NoSpace }.count)

        vm.select(AgentsFilter.NoSpace)
        runCurrent()

        assertEquals(listOf("t-bare"), vm.state.value.rows.map { it.address })
    }

    /** Each chip counts what it shows; a finished blocked agent is not waiting for the user. */
    @Test
    fun theChipsCountTheirAgentsAndOneFilterIsSelectedAtATime() = runTest {
        val (_, vm) = listing(
            terminalJson("t-blocked", status = "blocked"),
            terminalJson("t-working", status = "working"),
            terminalJson("t-idle", status = "idle"),
            terminalJson("t-done", status = "done"),
            terminalJson("t-finished", state = "finished", status = "blocked"),
        )

        val state = vm.state.value
        // All, Needs you, Working, Idle, and the terminals no space claims.
        assertEquals(listOf(5, 1, 1, 1, 0), state.chips.map { it.count })
        assertEquals(listOf(true, false, false, false, false), state.chips.map { it.selected })
        assertEquals(1, state.blockedCount)

        vm.select(AgentsFilter.NeedsYou)

        assertEquals(listOf("t-blocked"), vm.state.value.rows.map { it.address })
        assertEquals(listOf(false, true, false, false, false), vm.state.value.chips.map { it.selected })
    }

    /** The rows keep the screen's order: waiting first, then working, done, idle, then no status, then the finished. */
    @Test
    fun theRowsFollowTheAgentsScreenOrder() = runTest {
        val (_, vm) = listing(
            terminalJson("t-idle", status = "idle"),
            terminalJson("t-plain"),
            terminalJson("t-finished", state = "finished", status = "working"),
            terminalJson("t-working", status = "working"),
            terminalJson("t-blocked", status = "blocked"),
            terminalJson("t-done", status = "done"),
        )

        assertEquals(
            // What needs you first, then the live ones in the order the snapshot gave them, finished last (0.5.1).
            listOf("t-blocked", "t-idle", "t-plain", "t-working", "t-done", "t-finished"),
            vm.state.value.rows.map { it.address },
        )
    }

    /** A row names the machine and carries its status; an unlabelled agent has no space. */
    @Test
    fun aRowNamesTheMachineAndItsStatus() = runTest {
        val (_, vm) = listing(terminalJson("t-blocked", status = "blocked"))

        val row = vm.state.value.rows.single()
        assertEquals("mac", row.machineName)
        assertEquals(null, row.spaceName)
        assertEquals(AgentStatus.Blocked, row.status)
        assertTrue(row.waiting)
    }
}
