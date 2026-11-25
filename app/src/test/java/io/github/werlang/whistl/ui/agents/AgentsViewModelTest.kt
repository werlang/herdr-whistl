package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.StaticInstalled
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.desktopDocJson
import io.github.werlang.whistl.support.desktopSpaceJson
import io.github.werlang.whistl.support.desktopTabJson
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.spaceJson
import io.github.werlang.whistl.support.spacesDocJson
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val vm = AgentsViewModel(h.agents, h.machines, FakePins(), h.spaces, StaticInstalled())
        runCurrent()
        return h to vm
    }

    /** A stack whose desktop document is [desktop], with [terminals] in its events snapshot. */
    private suspend fun TestScope.listingWithDesktop(
        desktop: JsonObject,
        vararg terminals: JsonObject,
    ): Pair<RepoStack, AgentsViewModel> {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(emptyList())
        h.core.desktop = desktop
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, terminals.toList())) }
        h.addMachine()
        runCurrent()
        val vm = AgentsViewModel(h.agents, h.machines, FakePins(), h.spaces, StaticInstalled())
        runCurrent()
        return h to vm
    }

    /** A desktop document with one space `gladcode2`, a tab `tb1`, and the folder the desktop document really has. */
    private fun gladcodeDesktop(tabName: String? = "Claude Code"): JsonObject =
        desktopDocJson(
            listOf(
                desktopSpaceJson(
                    "sp9aed68",
                    "gladcode2",
                    "/root",
                    listOf(desktopTabJson("tb1", tabName)),
                ),
            ),
        )

    /** With no agents, every chip counts zero, there are no rows, and the badge is hidden. */
    @Test
    fun anEmptyListHasNoRowsAndZeroCounts() = runTest {
        val (_, vm) = listing()

        val state = vm.state.value
        assertEquals(listOf(0, 0, 0, 0, 0), state.chips.map { it.count })
        assertTrue(state.rows.isEmpty())
        assertEquals(0, state.blockedCount)
    }

    /** New agent is a space's: with the whole list showing, there is nothing to offer (0.6.5). */
    @Test
    fun newAgentIsOfferedOnlyWhenTheListShowsOneSpace() = runTest {
        val (_, vm) = listing(terminalJson("t-bound"))

        assertNull("no space filter, no New agent", vm.state.value.newAgent)
        assertNull(vm.state.value.picker)
    }

    /** The list keeps naming the space it is filtered to even when that space has no agents (0.7.3). */
    @Test
    fun theFilteredSpacesNameIsKeptWhenItHasNoAgents() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(listOf(spaceJson("sp_a", "web", "/home/u/web")))
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, emptyList())) }
        val machineId = h.addMachine()
        runCurrent()

        val vm = AgentsViewModel(
            h.agents,
            h.machines,
            FakePins(),
            h.spaces,
            StaticInstalled(),
            MutableStateFlow<SpaceFilter?>(SpaceFilter(machineId, "sp_a")),
        )
        runCurrent()

        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals("web", vm.state.value.filterName)
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
        // All, Needs you, Working, Idle, and the terminals no space claims. The finished one is counted nowhere: it has no
        // screen to open and nothing to send (0.7.3).
        assertEquals(listOf(4, 1, 1, 1, 0), state.chips.map { it.count })
        assertEquals(listOf(true, false, false, false, false), state.chips.map { it.selected })
        assertEquals(1, state.blockedCount)

        vm.select(AgentsFilter.NeedsYou)

        assertEquals(listOf("t-blocked"), vm.state.value.rows.map { it.address })
        assertEquals(listOf(false, true, false, false, false), vm.state.value.chips.map { it.selected })
    }

    /** The rows keep the screen's order: waiting first, then working, done, idle, then no status. A finished one is not a row. */
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
            // What needs you first, then the live ones in the order the snapshot gave them (0.5.1). The finished terminal
            // is not in the list at all (0.7.3).
            listOf("t-blocked", "t-idle", "t-plain", "t-working", "t-done"),
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

    /**
     * The name comes from the desktop tab whose id the terminal carries, matched exactly. The core sends no name and the
     * dynamic title is ignored; a tab name with " / " in it stays whole, which the `where` string cannot promise.
     */
    @Test
    fun aTerminalInheritsItsExactDesktopTabName() = runTest {
        val terminal = terminalJson(
            "t-tab",
            name = null,
            labels = mapOf(
                "herdr.space" to "sp9aed68",
                "herdr.tab" to "tb1",
                "where" to "gladcode2 / Wrong / Name",
            ),
        )

        val (h, vm) = listingWithDesktop(gladcodeDesktop(tabName = "Fix / Deploy"), terminal)

        assertEquals(mapOf("tb1" to "Fix / Deploy"), h.spaces.groups().value.single().tabNames)
        val repoAgent = h.agents.agents.value.single()
        assertEquals("tb1", repoAgent.agent.terminal.labels["herdr.tab"])
        assertEquals("Fix / Deploy", repoAgent.tabName)
        assertEquals("gladcode2", vm.state.value.rows.single().spaceName)
        assertEquals("Fix / Deploy", vm.state.value.rows.single().name)
    }

    /** With no tab name in the document, `labels.where` gives the tab's name after the exact space prefix. */
    @Test
    fun aTerminalUsesTheWhereLabelWhenTheTabNameIsMissing() = runTest {
        val terminal = terminalJson(
            "t-where",
            name = null,
            labels = mapOf(
                "herdr.space" to "sp9aed68",
                "where" to "gladcode2 / Codex",
            ),
        )

        val (_, vm) = listingWithDesktop(gladcodeDesktop(tabName = null), terminal)

        assertEquals("Codex", vm.state.value.rows.single().name)
    }

    /** A Whistl Rename of the space does not break the `where` prefix, which keeps the raw Herdr name. */
    @Test
    fun aRenamedSpaceStillSplitsWhereByTheHerdrName() = runTest {
        val terminal = terminalJson(
            "t-where",
            name = null,
            labels = mapOf("herdr.space" to "sp9aed68", "where" to "gladcode2 / Fix / Deploy"),
        )
        val (h, vm) = listingWithDesktop(gladcodeDesktop(tabName = null), terminal)
        val machineId = h.machines.machines().value.single().id

        assertEquals("Fix / Deploy", vm.state.value.rows.single().name)
        assertEquals(io.github.werlang.whistl.data.SpaceResult.Done("sp9aed68"), h.spaces.rename(machineId, "sp9aed68", "mine"))
        runCurrent()

        assertEquals("mine", vm.state.value.rows.single().spaceName)
        assertEquals("Fix / Deploy", vm.state.value.rows.single().name)
    }

    /** The top-level `where` some hc paths compose is accepted too, for the sample `hc ls` prints. */
    @Test
    fun aTerminalUsesTheTopLevelWhere() = runTest {
        val terminal = terminalJson(
            "t-where-top",
            name = null,
            labels = mapOf("herdr.space" to "sp9aed68"),
            where = "gladcode2 / OpenCode",
        )

        val (_, vm) = listingWithDesktop(gladcodeDesktop(tabName = null), terminal)

        assertEquals("OpenCode", vm.state.value.rows.single().name)
    }

    /** The explicit Whistl rename wins over the desktop tab and over `where`. */
    @Test
    fun aCustomNameWinsOverTheDesktopTab() = runTest {
        val terminal = terminalJson(
            "t-named",
            name = null,
            labels = mapOf(
                "herdr.space" to "sp9aed68",
                "herdr.tab" to "tb1",
                "where" to "gladcode2 / Claude Code",
                "whistl.name" to "Deploy",
            ),
        )

        val (_, vm) = listingWithDesktop(gladcodeDesktop(), terminal)

        assertEquals("Deploy", vm.state.value.rows.single().name)
    }

    /** The explicit name the core was given is preserved when no tab or where supplies one. */
    @Test
    fun anExplicitCoreNameIsPreserved() = runTest {
        val terminal = terminalJson(
            "t-core",
            name = "my terminal",
            labels = mapOf("herdr.space" to "sp9aed68"),
        )

        val (_, vm) = listingWithDesktop(gladcodeDesktop(tabName = null), terminal)

        assertEquals("my terminal", vm.state.value.rows.single().name)
    }

    /** Without any name, the core's `status.app` identifies the agent; nothing else is invented. */
    @Test
    fun aTerminalFallsBackToItsCoreAgent() = runTest {
        val terminal = terminalJson(
            "t-app",
            name = null,
            app = "codex",
            labels = mapOf("herdr.space" to "sp9aed68"),
        )

        val (_, vm) = listingWithDesktop(gladcodeDesktop(tabName = null), terminal)

        assertEquals("codex", vm.state.value.rows.single().name)
    }
}
