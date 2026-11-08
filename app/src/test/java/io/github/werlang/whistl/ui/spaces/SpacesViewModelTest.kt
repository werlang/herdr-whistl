package io.github.werlang.whistl.ui.spaces

import io.github.werlang.whistl.data.FolderResolution
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.UnresolvableReason
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.terminalJson
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.support.testHello
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Spaces screen (SPEC §2.2, phase 4b r2): grouping, the repository's order, the waiting badge, the offline and unresolved
 * rows, the move and pin rules, rename and close, and New space. The order, create, rename, move and close tests run on the real
 * repositories ([RepoStack]); the state tests use fixed spaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpacesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /**
     * A terminal, with the fields the screens read.
     *
     * @param address its address.
     * @param status its status.
     * @param state live or finished.
     * @param labels its labels.
     * @return the terminal.
     */
    private fun terminal(
        address: String,
        status: AgentStatus?,
        state: TerminalState = TerminalState.Live,
        labels: Map<String, String> = emptyMap(),
    ): Terminal = Terminal(
        address = address,
        name = address,
        argv = listOf("sh"),
        cwd = null,
        labels = labels,
        state = state,
        status = status,
        turn = 0,
        exitCode = null,
        createdMs = null,
        finishedMs = null,
        title = null,
        pid = null,
        signal = null,
        reason = null,
        keep = false,
    )

    /**
     * One machine's group over the given spaces.
     *
     * @param spaces the spaces, in display order.
     * @param offline whether the machine is offline.
     * @param resolutions the folder resolutions by space id.
     * @return the group.
     */
    private fun group(
        spaces: List<SpaceRecord>,
        offline: Boolean = false,
        resolutions: Map<String, FolderResolution> = emptyMap(),
    ): MachineSpaces = MachineSpaces(machineId = MACHINE, offline = offline, spaces = spaces, resolutions = resolutions)

    /** A space record with the given name, pin state and order. */
    private fun space(id: String, name: String, pinned: Boolean = false, order: Int = 0): SpaceRecord =
        SpaceRecord(id = id, name = name, folder = "~/$name", pinned = pinned, order = order)

    /** One machine, connected, in a fixed list of machines. */
    private fun machines(): ScriptedMachines {
        val machines = ScriptedMachines()
        machines.put(Machine(id = MACHINE, name = "build", host = "build.lan", port = 22, user = "ci"), ConnectionState.Connected(testHello()))
        return machines
    }

    /** The rows of the first group, by name. */
    private fun rowNames(vm: SpacesViewModel): List<String> = vm.state.value.groups.first().rows.map { it.name }

    /**
     * Pinned spaces come first, then the user's order: a repository-level order, shown as the repository gives it. The names
     * are fixed, so breaking the ordering (in `sortSpaces` or in grouping) fails this test.
     */
    @Test
    fun pinnedSpacesComeFirstThenTheUserOrder() = runTest {
        val stack = RepoStack(this)
        val machine = stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()
        val ids = listOf("one", "two", "three", "four").map { name ->
            (stack.spaces.create(machine, "~/$name", name) as SpaceResult.Done).spaceId
        }
        stack.spaces.setPinned(machine, ids[0], pinned = true)
        stack.spaces.setPinned(machine, ids[2], pinned = true)
        runCurrent()

        assertEquals(listOf("one", "three", "two", "four"), rowNames(vm))
        assertEquals(listOf(true, true, false, false), vm.state.value.groups.first().rows.map { it.pinned })
    }

    /** The view model keeps the order it is given: it does not sort, even when the list is out of order. */
    @Test
    fun theViewModelDoesNotResortTheRepositoryOrder() {
        val spaces = StaticSpaces(
            listOf(group(listOf(space("b", "beta", pinned = false, order = 1), space("a", "alpha", pinned = true, order = 0)))),
        )
        val vm = SpacesViewModel(spaces, StaticAgents(), machines())

        assertEquals(listOf("beta", "alpha"), rowNames(vm))
    }

    /** Each machine is one group, in machine order, with the machine's name and address. */
    @Test
    fun eachMachineIsOneGroupWithItsNameAndAddress() {
        val spaces = StaticSpaces(listOf(group(listOf(space("a", "alpha")))))
        val vm = SpacesViewModel(spaces, StaticAgents(), machines())

        val group = vm.state.value.groups.single()
        assertEquals("build", group.name)
        assertEquals("ci@build.lan", group.address)
        assertFalse(group.offline)
    }

    /** A space's waiting badge counts the agents of that space that wait for the user, and no other space's. */
    @Test
    fun theWaitingBadgeCountsTheBlockedAgentsOfItsSpace() {
        val web = space("web", "web")
        val docs = space("docs", "docs", order = 1)
        val blocked = MachineAgent(MACHINE, Agent(terminal("t-1", AgentStatus.Blocked, labels = mapOf(Agent.SPACE_LABEL to "web"))), web)
        val working = MachineAgent(MACHINE, Agent(terminal("t-2", AgentStatus.Working, labels = mapOf(Agent.SPACE_LABEL to "docs"))), docs)
        val vm = SpacesViewModel(StaticSpaces(listOf(group(listOf(web, docs)))), StaticAgents(listOf(blocked, working)), machines())

        val rows = vm.state.value.groups.single().rows.associateBy { it.name }
        assertEquals(1, rows.getValue("web").waitingCount)
        assertEquals(0, rows.getValue("docs").waitingCount)
        assertEquals(listOf(AgentDot(AgentStatus.Blocked, live = true, waiting = true)), rows.getValue("web").agents)
    }

    /** A finished blocked agent is not waiting for the user: it has no badge. */
    @Test
    fun aFinishedBlockedAgentDoesNotCountAsWaiting() {
        val web = space("web", "web")
        val finished = MachineAgent(
            MACHINE,
            Agent(terminal("t-1", AgentStatus.Blocked, state = TerminalState.Finished, labels = mapOf(Agent.SPACE_LABEL to "web"))),
            web,
        )
        val vm = SpacesViewModel(StaticSpaces(listOf(group(listOf(web)))), StaticAgents(listOf(finished)), machines())

        assertEquals(0, vm.state.value.groups.single().rows.single().waitingCount)
    }

    /** An offline machine's group is marked offline, and each of its rows is dimmed. */
    @Test
    fun anOfflineGroupIsDimmedAndSaysSo() {
        val vm = SpacesViewModel(
            StaticSpaces(listOf(group(listOf(space("a", "alpha")), offline = true))),
            StaticAgents(),
            machines(),
        )

        val group = vm.state.value.groups.single()
        assertTrue(group.offline)
        assertTrue(group.rows.single().dimmed)
    }

    /** A row shows its folder's typed reason when the folder is unavailable, and says it is not checked when it is not. */
    @Test
    fun aRowShowsItsFolderStateNeverANameInstead() {
        val resolutions = mapOf("a" to FolderResolution.Unresolvable(UnresolvableReason.NotFound), "b" to FolderResolution.Resolved("/home/ci/b"))
        val vm = SpacesViewModel(
            StaticSpaces(listOf(group(listOf(space("a", "alpha"), space("b", "beta"), space("c", "gamma")), resolutions = resolutions))),
            StaticAgents(),
            machines(),
        )

        val rows = vm.state.value.groups.single().rows.associateBy { it.name }
        assertEquals(FolderState.Unavailable(UnresolvableReason.NotFound), rows.getValue("alpha").folderState)
        assertEquals(FolderState.Resolved, rows.getValue("beta").folderState)
        assertEquals(FolderState.NotChecked, rows.getValue("gamma").folderState)
    }

    /**
     * The move rule stops at the edges of the pin section: a neighbour counts only in the same pin section. Pinned rows move
     * among themselves, and unpinned rows among themselves.
     */
    @Test
    fun moveRulesStopAtTheEdgesOfThePinSection() {
        val rows = listOf(
            space("p1", "p1", pinned = true, order = 0),
            space("p2", "p2", pinned = true, order = 1),
            space("u1", "u1", pinned = false, order = 2),
            space("u2", "u2", pinned = false, order = 3),
        )

        assertEquals(
            listOf(false to true, true to false, false to true, true to false),
            moveRules(rows),
        )
    }

    /** Move up and down change the order inside the pin section; at an edge nothing moves. */
    @Test
    fun moveUpAndDownChangeTheOrderAndNothingMovesAtTheEdge() = runTest {
        val stack = RepoStack(this)
        val machine = stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()
        val ids = listOf("a", "b", "c").map { name -> (stack.spaces.create(machine, "~/$name", name) as SpaceResult.Done).spaceId }
        runCurrent()
        assertEquals(listOf("a", "b", "c"), rowNames(vm))

        vm.openMenu(machine, ids[1])
        vm.moveUp()
        runCurrent()
        assertEquals(listOf("b", "a", "c"), rowNames(vm))

        vm.openMenu(machine, ids[1])
        assertFalse("b is now the first row", vm.state.value.groups.first().rows.first().canMoveUp)
        vm.moveUp()
        runCurrent()
        assertEquals("nothing moves at the edge", listOf("b", "a", "c"), rowNames(vm))

        vm.openMenu(machine, ids[2])
        assertFalse("c is the last row", vm.state.value.menu!!.canMoveDown)
        vm.moveDown()
        runCurrent()
        assertEquals("nothing moves at the edge", listOf("b", "a", "c"), rowNames(vm))
    }

    /** A rename with an empty name shows its problem under the field and writes nothing; a valid name is saved. */
    @Test
    fun renameValidatesTheNameBeforeSaving() = runTest {
        val stack = RepoStack(this)
        val machine = stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()
        val id = (stack.spaces.create(machine, "~/web", "web") as SpaceResult.Done).spaceId
        runCurrent()

        vm.openMenu(machine, id)
        vm.askRename()
        vm.editRename("")
        vm.confirmRename()
        runCurrent()
        assertEquals(SpaceProblem.NameEmpty, vm.state.value.rename?.problem)
        assertEquals("web", rowNames(vm).single())

        vm.editRename("web-app")
        vm.confirmRename()
        runCurrent()
        assertNull(vm.state.value.rename)
        assertEquals(listOf("web-app"), rowNames(vm))
    }

    /**
     * Close space asks first, and the confirmation counts the live agents of the space; confirming stops them and removes the
     * space.
     */
    @Test
    fun closeCountsTheSpaceAgentsAndConfirmingStopsThemAndRemovesTheSpace() = runTest {
        val stack = RepoStack(this)
        val machine = stack.addMachine()
        runCurrent()
        val id = (stack.spaces.create(machine, "~/web", "web") as SpaceResult.Done).spaceId
        // A live agent in the space: the events channel announces it, and the core lists it when the close looks for it.
        val agent = terminalJson("t-1", status = "working", labels = mapOf("whistl.space" to id))
        stack.pushEvent(eventLine("created", "t-1", 2, agent))
        stack.core.terminals += agent
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()

        vm.openMenu(machine, id)
        vm.askClose()
        runCurrent()
        assertEquals("one live agent is counted", 1, vm.state.value.close?.agentCount)

        vm.confirmClose()
        runCurrent()

        assertEquals("the agent was stopped", listOf("t-1"), stack.core.killed)
        assertNull(vm.state.value.close)
        assertTrue("the space left the list", vm.state.value.groups.single().rows.isEmpty())
    }

    /** New space: the first machine is the default, and the name placeholder is the folder's last segment as it is typed. */
    @Test
    fun newSpaceDefaultsToTheFirstMachineAndTheFolderLastSegment() {
        val vm = SpacesViewModel(StaticSpaces(emptyList()), StaticAgents(), machines())

        vm.openNewSpace()
        assertEquals(MACHINE, vm.state.value.newSpace?.selectedId)
        assertEquals("~", vm.state.value.newSpace?.defaultName)

        vm.editFolder("~/code/web-app")
        assertEquals("web-app", vm.state.value.newSpace?.defaultName)
    }

    /** A blank folder is `~`, and a blank name takes the folder's last segment, as the repository defines them. */
    @Test
    fun aBlankFolderAndNameUseTheDefaults() = runTest {
        val stack = RepoStack(this)
        val machine = stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()

        vm.openNewSpace()
        vm.createSpace()
        runCurrent()

        assertNull("the sheet closed on success", vm.state.value.newSpace)
        val created = vm.state.value.groups.single().rows.single()
        assertEquals("~", created.folder)
        assertEquals("the folder's last segment", "~", created.name)
        assertEquals(machine, created.machineId)
    }

    /** A folder with a line break is rejected under the folder field, and nothing is created. */
    @Test
    fun aFolderWithALineBreakIsRejectedUnderTheFolder() = runTest {
        val stack = RepoStack(this)
        stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()

        vm.openNewSpace()
        vm.editFolder("~/a\nb")
        vm.createSpace()
        runCurrent()

        assertEquals(SpaceProblem.FolderBad, vm.state.value.newSpace?.folderProblem)
        assertTrue(vm.state.value.groups.single().rows.isEmpty())
    }

    /** A name longer than the limit is rejected under the name field. */
    @Test
    fun aTooLongNameIsRejectedUnderTheName() = runTest {
        val stack = RepoStack(this)
        stack.addMachine()
        runCurrent()
        val vm = SpacesViewModel(stack.spaces, stack.agents, stack.machines)
        runCurrent()

        vm.openNewSpace()
        vm.editName("n".repeat(65))
        vm.createSpace()
        runCurrent()

        assertEquals(SpaceProblem.NameTooLong, vm.state.value.newSpace?.nameProblem)
    }

    /** A machine that is offline cannot take a new space: the sheet says so. */
    @Test
    fun aNewSpaceOnAnOfflineMachineSaysSo() {
        val vm = SpacesViewModel(StaticSpaces(emptyList()), StaticAgents(), machines())

        vm.openNewSpace()
        vm.createSpace()

        assertTrue(vm.state.value.newSpace?.machineOffline == true)
    }

    /** With no machine at all, the screen has no rows, no machine to pick, and says there is no machine. */
    @Test
    fun withNoMachineThereAreNoChoicesAndNoRows() {
        val vm = SpacesViewModel(StaticSpaces(emptyList()), StaticAgents(), ScriptedMachines())

        vm.openNewSpace()

        assertFalse(vm.state.value.hasMachines)
        assertTrue(vm.state.value.isEmpty)
        assertTrue(vm.state.value.newSpace?.machines.orEmpty().isEmpty())
        assertNull(vm.state.value.newSpace?.selectedId)
    }

    /** A machine with no spaces is an empty list on a machine that exists. */
    @Test
    fun aMachineWithNoSpacesIsEmptyButStillListed() {
        val vm = SpacesViewModel(StaticSpaces(listOf(group(emptyList()))), StaticAgents(), machines())

        assertTrue(vm.state.value.hasMachines)
        assertTrue(vm.state.value.isEmpty)
        assertEquals(1, vm.state.value.groups.size)
    }

    /** The row menu of an offline machine's space has its edits off, so no sheet opens for them. */
    @Test
    fun theMenuOfAnOfflineSpaceHasItsEditsOff() {
        val vm = SpacesViewModel(
            StaticSpaces(listOf(group(listOf(space("a", "alpha")), offline = true))),
            StaticAgents(),
            machines(),
        )

        vm.openMenu(MACHINE, "a")
        assertTrue(vm.state.value.menu?.offline == true)
        vm.askRename()
        assertNull(vm.state.value.rename)
        vm.askClose()
        assertNull(vm.state.value.close)
    }

    private companion object {
        const val MACHINE = "m1"
    }
}
