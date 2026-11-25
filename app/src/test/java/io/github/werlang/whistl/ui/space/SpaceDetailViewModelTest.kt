package io.github.werlang.whistl.ui.space

import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.data.FolderResolution
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.data.UnresolvableReason
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.KnownAgent
import io.github.werlang.whistl.domain.KnownAgents
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticInstalled
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.testHello
import io.github.werlang.whistl.ui.machines.MachineIssue
import io.github.werlang.whistl.ui.machines.MachineStatus
import io.github.werlang.whistl.ui.spaces.FolderState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Space detail (SPEC §2.3, phase 4b r2): the header, the space's agents, the New agent button (off while offline and while
 * the folder is not resolved), the picker's choices, the start's outcomes, and the space's own overflow menu (Pin/Unpin, Move
 * up, Move down, Rename, Close space, off on an offline machine). The agents' start answers are scripted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpaceDetailViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** An agents repository whose agents are fixed and whose start answers the test chooses; it records each start. */
    private class ScriptedAgents(
        agents: List<MachineAgent>,
        var answer: StartResult = StartResult.Offline,
    ) : AgentsRepository {
        override val agents: StateFlow<List<MachineAgent>> = MutableStateFlow(agents)

        override val blockedCount: StateFlow<Int> = MutableStateFlow(0)

        /** The choices the picker started, in order. */
        val started = mutableListOf<StartChoice>()

        /** The list's menu is not part of these tests. */
        override suspend fun act(machineId: String, address: String, action: io.github.werlang.whistl.data.AgentAction): io.github.werlang.whistl.data.ActionResult =
            io.github.werlang.whistl.data.ActionResult.Gone

        override suspend fun start(machineId: String, spaceId: String, choice: StartChoice): StartResult {
            started += choice
            return answer
        }
    }

    /**
     * A spaces repository whose groups are fixed and whose edits are recorded, so the space menu's calls can be asserted. Its
     * answers do not change the groups: these tests are about what the menu asks for.
     *
     * @param initial the groups.
     */
    private class RecordingSpaces(initial: List<MachineSpaces>) : SpacesRepository {
        private val groupsFlow = MutableStateFlow(initial)

        /** Each pin, as machine, space and the value asked for. */
        val pinned = mutableListOf<Triple<String, String, Boolean>>()

        /** Each move, as `up` or `down`. */
        val moved = mutableListOf<String>()

        /** Each rename, as machine, space and the name asked for. */
        val renamed = mutableListOf<Triple<String, String, String>>()

        /** Each close, as machine and space. */
        val closed = mutableListOf<Pair<String, String>>()

        override fun groups(): StateFlow<List<MachineSpaces>> = groupsFlow

        override suspend fun create(machineId: String, folder: String, name: String): SpaceResult = SpaceResult.MachineOffline

        override suspend fun rename(machineId: String, spaceId: String, name: String): SpaceResult {
            renamed += Triple(machineId, spaceId, name)
            return SpaceResult.Done(spaceId)
        }

        override suspend fun setPinned(machineId: String, spaceId: String, pinned: Boolean): SpaceResult {
            this.pinned += Triple(machineId, spaceId, pinned)
            return SpaceResult.Done(spaceId)
        }

        override suspend fun moveUp(machineId: String, spaceId: String): SpaceResult {
            moved += "up"
            return SpaceResult.Done(spaceId)
        }

        override suspend fun moveDown(machineId: String, spaceId: String): SpaceResult {
            moved += "down"
            return SpaceResult.Done(spaceId)
        }

        override suspend fun close(machineId: String, spaceId: String): SpaceResult {
            closed += machineId to spaceId
            return SpaceResult.Done(spaceId)
        }
    }

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

    /** The space under test: `web` on machine `m1`. */
    private val web = SpaceRecord(id = SPACE, name = "web", folder = "~/web", pinned = true, order = 0)

    /**
     * One machine's group.
     *
     * @param offline whether the machine is offline.
     * @param resolution the folder's resolution.
     * @param records the spaces of the machine, in display order.
     * @return the group.
     */
    private fun group(
        offline: Boolean = false,
        resolution: FolderResolution? = FolderResolution.Resolved("/home/ci/web"),
        records: List<SpaceRecord> = listOf(web),
    ): MachineSpaces =
        MachineSpaces(
            machineId = MACHINE,
            offline = offline,
            spaces = records,
            resolutions = resolution?.let { mapOf(SPACE to it) }.orEmpty(),
        )

    /** The machine `m1`, named `build`. */
    private fun machine(): Machine = Machine(id = MACHINE, name = "build", host = "build.lan", port = 22, user = "ci")

    /** The connected machine `m1`, named `build`. */
    private fun machines(): ScriptedMachines {
        val machines = ScriptedMachines()
        machines.put(machine(), ConnectionState.Connected(testHello()))
        return machines
    }

    /** The offline machine `m1`, named `build`. */
    private fun offlineMachines(): ScriptedMachines {
        val machines = ScriptedMachines()
        machines.put(machine(), ConnectionState.Offline)
        return machines
    }

    /**
     * The view model for the space, over the given fixed data.
     *
     * @param groups the groups the spaces repository reports.
     * @param agents the agents repository.
     * @param installed the installed agents of each machine.
     * @param machineRepo the machines, and so the state the header names.
     * @return the view model.
     */
    private fun model(
        groups: List<MachineSpaces>,
        agents: AgentsRepository = ScriptedAgents(emptyList()),
        installed: Map<String, List<KnownAgent>> = emptyMap(),
        machineRepo: MachinesRepository = machines(),
    ): SpaceDetailViewModel = SpaceDetailViewModel(
        machineId = MACHINE,
        spaceId = SPACE,
        spaces = StaticSpaces(groups),
        agents = agents,
        machines = machineRepo,
        installed = StaticInstalled(installed),
        pins = FakePins(),
    )

    /**
     * The view model over a spaces repository the test keeps, so the space menu's calls can be read.
     *
     * @param spaces the repository.
     * @param agents the agents repository.
     * @param machineRepo the machines, and so the state the header names.
     * @return the view model.
     */
    private fun modelWith(
        spaces: SpacesRepository,
        agents: AgentsRepository = ScriptedAgents(emptyList()),
        machineRepo: MachinesRepository = machines(),
    ): SpaceDetailViewModel = SpaceDetailViewModel(
        machineId = MACHINE,
        spaceId = SPACE,
        spaces = spaces,
        agents = agents,
        machines = machineRepo,
        installed = StaticInstalled(emptyMap()),
        pins = FakePins(),
    )

    /** The header is the space's name, its machine's name, and its folder. */
    @Test
    fun theHeaderNamesTheSpaceItsMachineAndItsFolder() {
        val state = model(listOf(group())).state.value

        assertEquals("web", state.name)
        assertEquals("build", state.machineName)
        assertEquals("~/web", state.folder)
        assertFalse(state.missing)
    }

    /**
     * The header carries the machine's real state, not only offline: a connecting machine and an error with its issue, so the
     * screen says what the user has to fix.
     */
    @Test
    fun theHeaderCarriesTheMachinesRealStatusAndIssue() {
        val cases = listOf(
            ConnectionState.Connecting to (MachineStatus.Connecting to null),
            ConnectionState.Error(ErrorKind.AuthFailed) to (MachineStatus.Error to MachineIssue.LoginRefused),
            ConnectionState.HostKeyUnknown("ssh-ed25519", "SHA256:new") to
                (MachineStatus.Error to MachineIssue.HostKeyToConfirm),
        )
        cases.forEach { (connection, expected) ->
            val machines = ScriptedMachines()
            machines.put(machine(), connection)

            val state = model(listOf(group(offline = true)), machineRepo = machines).state.value

            assertEquals("status for $connection", expected.first, state.machineStatus)
            assertEquals("issue for $connection", expected.second, state.machineIssue)
            assertEquals("name for $connection", "build", state.machineName)
        }
    }

    /** An offline group whose machine is not in the list any more keeps the offline default. */
    @Test
    fun anOfflineGroupWithoutItsMachineStaysOffline() {
        val state = model(listOf(group(offline = true)), machineRepo = ScriptedMachines()).state.value

        assertEquals(MachineStatus.Offline, state.machineStatus)
        assertNull(state.machineIssue)
    }

    /** The rows are the agents of this space only, each with its own name and status. */
    @Test
    fun theRowsAreTheAgentsOfThisSpaceOnly() {
        val mine = MachineAgent(MACHINE, Agent(terminal("t-1", AgentStatus.Working, labels = mapOf(Agent.SPACE_LABEL to SPACE))), web)
        val other = MachineAgent(
            MACHINE,
            Agent(terminal("t-2", AgentStatus.Idle, labels = mapOf(Agent.SPACE_LABEL to "sp_other"))),
            SpaceRecord(id = "sp_other", name = "other", folder = "~/other", pinned = false, order = 1),
        )

        val rows = model(listOf(group()), agents = ScriptedAgents(listOf(mine, other))).state.value.agents

        assertEquals(listOf("t-1"), rows.map { it.address })
        assertEquals(AgentStatus.Working, rows.single().status)
        assertTrue(rows.single().live)
    }

    /** A row inherits the terminal's `where` tab name after the exact space prefix, not the core's argv or address. */
    @Test
    fun theRowsInheritTheWhereTabName() {
        val named = MachineAgent(
            MACHINE,
            Agent(
                terminal(
                    "t-1",
                    AgentStatus.Working,
                    labels = mapOf(Agent.HERDR_SPACE_LABEL to SPACE, "where" to "web / Claude Code"),
                ),
            ),
            web,
        )

        val rows = model(listOf(group()), agents = ScriptedAgents(listOf(named))).state.value.agents

        assertEquals("Claude Code", rows.single().name)
    }

    /** New agent is on when the machine is connected and the folder is resolved. */
    @Test
    fun theNewAgentButtonIsOnWhenConnectedAndTheFolderIsResolved() {
        val vm = model(listOf(group()))

        assertTrue(vm.state.value.newAgent.enabled)
        assertNull(vm.state.value.newAgent.block)

        vm.openPicker()
        assertTrue("the picker opens", vm.state.value.picker != null)
    }

    /**
     * New agent is off while the machine is offline, and the picker does not open from it: the FAB offline check. The header
     * reads the machine's real state.
     */
    @Test
    fun theNewAgentIsOffWhileTheMachineIsOfflineAndThePickerDoesNotOpen() {
        val vm = model(listOf(group(offline = true)), machineRepo = offlineMachines())

        assertFalse(vm.state.value.newAgent.enabled)
        assertEquals(NewAgentBlock.MachineOffline, vm.state.value.newAgent.block)
        assertEquals(MachineStatus.Offline, vm.state.value.machineStatus)
        assertNull(vm.state.value.machineIssue)

        vm.openPicker()
        assertNull("no picker from an offline FAB", vm.state.value.picker)
    }

    /** A space with no folder (the desktop's `folder: ""`) has no pending check: it reads as None and cannot start an agent. */
    @Test
    fun aSpaceWithoutAFolderHasNoPendingCheckAndCannotStartAnAgent() {
        val blank = SpaceRecord(id = SPACE, name = "gladcode2", folder = "", pinned = false, order = 0)
        val vm = model(listOf(group(records = listOf(blank), resolution = null)))

        assertEquals(FolderState.None, vm.state.value.folderState)
        assertFalse(vm.state.value.newAgent.enabled)
        assertEquals(NewAgentBlock.Folder(FolderState.None), vm.state.value.newAgent.block)
        vm.openPicker()
        assertNull("there is no folder to start in", vm.state.value.picker)
    }

    /** New agent is off on an unresolved folder, and says why. */
    @Test
    fun theNewAgentIsOffOnAnUnresolvedFolderAndSaysWhy() {
        val vm = model(listOf(group(resolution = FolderResolution.Unresolvable(UnresolvableReason.NotFound))))

        assertFalse(vm.state.value.newAgent.enabled)
        assertEquals(NewAgentBlock.Folder(FolderState.Unavailable(UnresolvableReason.NotFound)), vm.state.value.newAgent.block)
        vm.openPicker()
        assertNull(vm.state.value.picker)
    }

    /** A folder not checked on this device yet is also off, with the not-checked state as its reason. */
    @Test
    fun aFolderNotCheckedYetIsOff() {
        val vm = model(listOf(group(resolution = null)))

        assertEquals(NewAgentBlock.Folder(FolderState.NotChecked), vm.state.value.newAgent.block)
        assertFalse(vm.state.value.newAgent.enabled)
    }

    /** The picker offers the installed agents of the machine, then Terminal. */
    @Test
    fun thePickerOffersTheInstalledAgentsThenTerminal() {
        val claude = KnownAgents.table.first { it.id == "claude" }
        val codex = KnownAgents.table.first { it.id == "codex" }
        val vm = model(listOf(group()), installed = mapOf(MACHINE to listOf(claude, codex)))

        vm.openPicker()

        assertEquals(
            listOf(StartChoice.Known(claude), StartChoice.Known(codex), StartChoice.Terminal),
            vm.state.value.picker?.choices,
        )
    }

    /** A machine with no installed agents still offers Terminal. */
    @Test
    fun thePickerOffersTerminalWhenNothingIsInstalled() {
        val vm = model(listOf(group()))

        vm.openPicker()

        assertEquals(listOf(StartChoice.Terminal), vm.state.value.picker?.choices)
    }

    /** A start that succeeds opens the new agent once, and closes the picker. */
    @Test
    fun aStartOpensTheNewAgentOnce() = runTest {
        val agents = ScriptedAgents(emptyList(), answer = StartResult.Started("t-9"))
        val vm = model(listOf(group()), agents = agents)
        vm.openPicker()

        vm.start(StartChoice.Terminal)
        runCurrent()

        assertEquals(listOf(StartChoice.Terminal), agents.started)
        assertEquals("t-9", vm.state.value.opened)
        assertNull("the picker closed", vm.state.value.picker)

        vm.consumeOpened()
        assertNull(vm.state.value.opened)
    }

    /** A start the machine refuses shows its typed problem in the picker, and opens nothing. */
    @Test
    fun aFailedStartShowsItsProblemInThePicker() = runTest {
        val agents = ScriptedAgents(emptyList(), answer = StartResult.CouldNotStart)
        val vm = model(listOf(group()), agents = agents)
        vm.openPicker()

        vm.start(StartChoice.Known(KnownAgents.table.first()))
        runCurrent()

        assertEquals(StartProblem.CouldNotStart, vm.state.value.picker?.problem)
        assertNull(vm.state.value.opened)
    }

    /** A folder the machine cannot use shows that in the picker, with its reason. */
    @Test
    fun aFolderUnavailableStartShowsTheReason() = runTest {
        val agents = ScriptedAgents(emptyList(), answer = StartResult.FolderUnavailable(UnresolvableReason.Timeout))
        val vm = model(listOf(group()), agents = agents)
        vm.openPicker()

        vm.start(StartChoice.Terminal)
        runCurrent()

        assertEquals(StartProblem.FolderUnavailable(UnresolvableReason.Timeout), vm.state.value.picker?.problem)
    }

    /** A space that is no longer on the machine is reported as missing: the screen offers Back. */
    @Test
    fun aSpaceThatIsGoneIsReportedAsMissing() {
        val vm = model(listOf(MachineSpaces(machineId = MACHINE, offline = false, spaces = emptyList())))

        assertTrue(vm.state.value.missing)
    }

    /** The space's own overflow menu opens with the space and its pin-section move flags, and closes. */
    @Test
    fun theSpaceMenuOpensWithTheSpaceAndItsMoveFlags() {
        val above = SpaceRecord(id = "sp_above", name = "above", folder = "~/above", pinned = true, order = 0)
        val below = SpaceRecord(id = "sp_below", name = "below", folder = "~/below", pinned = true, order = 2)
        val vm = model(listOf(group(records = listOf(above, web, below))))

        vm.openSpaceMenu()

        val menu = vm.state.value.spaceMenu
        assertEquals("web", menu?.name)
        assertTrue(menu?.pinned == true)
        assertTrue("a pinned row is above", menu?.canMoveUp == true)
        assertTrue("a pinned row is below", menu?.canMoveDown == true)
        assertFalse(menu?.offline == true)

        vm.closeSpaceMenu()
        assertNull(vm.state.value.spaceMenu)
    }

    /** Pin and Move act on the space, and each closes the menu; a move stops at the edge of the pin section. */
    @Test
    fun theSpaceMenuPinsAndMovesTheSpaceInItsPinSection() = runTest {
        val above = SpaceRecord(id = "sp_above", name = "above", folder = "~/above", pinned = true, order = 1)
        val spaces = RecordingSpaces(listOf(group(records = listOf(above, web))))
        val vm = modelWith(spaces)

        vm.openSpaceMenu()
        vm.toggleSpacePin()
        runCurrent()

        assertEquals(listOf(Triple(MACHINE, SPACE, false)), spaces.pinned)
        assertNull("the menu closes on the action", vm.state.value.spaceMenu)

        vm.openSpaceMenu()
        vm.moveSpaceUp()
        runCurrent()
        assertEquals(listOf("up"), spaces.moved)

        vm.openSpaceMenu()
        vm.moveSpaceDown()
        runCurrent()
        assertEquals("the space is last, so nothing moves down", listOf("up"), spaces.moved)
    }

    /** Rename opens the space's name in the sheet and writes the typed one. */
    @Test
    fun theSpaceMenuRenamesTheSpace() = runTest {
        val spaces = RecordingSpaces(listOf(group()))
        val vm = modelWith(spaces)

        vm.openSpaceMenu()
        vm.askRenameSpace()

        assertEquals("web", vm.state.value.spaceRename?.draft)
        assertNull("the menu gives way to the sheet", vm.state.value.spaceMenu)

        vm.editRenameSpace("web-app")
        vm.confirmRenameSpace()
        runCurrent()

        assertEquals(listOf(Triple(MACHINE, SPACE, "web-app")), spaces.renamed)
        assertNull(vm.state.value.spaceRename)
    }

    /** Close asks with the live agent count, closes the space, and tells the screen to leave. */
    @Test
    fun closingTheSpaceCountsItsLiveAgentsAndLeavesTheScreen() = runTest {
        val live = MachineAgent(
            MACHINE,
            Agent(terminal("t-1", AgentStatus.Working, labels = mapOf(Agent.SPACE_LABEL to SPACE))),
            web,
        )
        val spaces = RecordingSpaces(listOf(group()))
        val vm = modelWith(spaces, agents = ScriptedAgents(listOf(live)))

        vm.openSpaceMenu()
        vm.askCloseSpace()

        assertEquals(1, vm.state.value.spaceClose?.agentCount)

        vm.confirmCloseSpace()
        runCurrent()

        assertEquals(listOf(MACHINE to SPACE), spaces.closed)
        assertTrue("the screen leaves once closed", vm.state.value.closed)
        assertNull(vm.state.value.spaceClose)
    }

    /** On an offline machine the space's menu opens to explain, but every action is off and no sheet opens. */
    @Test
    fun theSpaceMenuOfAnOfflineMachineHasItsActionsOff() = runTest {
        val above = SpaceRecord(id = "sp_above", name = "above", folder = "~/above", pinned = true, order = 1)
        val spaces = RecordingSpaces(listOf(group(offline = true, records = listOf(above, web))))
        val vm = modelWith(spaces, machineRepo = offlineMachines())

        vm.openSpaceMenu()

        assertTrue(vm.state.value.spaceMenu?.offline == true)
        assertTrue("a move would be allowed on a connected machine", vm.state.value.spaceMenu?.canMoveUp == true)

        vm.toggleSpacePin()
        vm.moveSpaceUp()
        vm.moveSpaceDown()
        vm.askRenameSpace()
        runCurrent()

        assertTrue("no action ran, so the menu stays open", vm.state.value.spaceMenu != null)
        assertNull("no rename sheet", vm.state.value.spaceRename)
        assertTrue(spaces.pinned.isEmpty())
        assertTrue(spaces.moved.isEmpty())

        vm.askCloseSpace()
        assertNull("no close confirmation", vm.state.value.spaceClose)
    }

    private companion object {
        const val MACHINE = "m1"
        const val SPACE = "sp_web"
    }
}
