package io.github.werlang.whistl.ui.space

import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.FolderResolution
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceRecord
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
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticInstalled
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.testHello
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
 * the folder is not resolved), the picker's choices, and the start's outcomes. The agents' start answers are scripted.
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
     * @return the group.
     */
    private fun group(offline: Boolean = false, resolution: FolderResolution? = FolderResolution.Resolved("/home/ci/web")): MachineSpaces =
        MachineSpaces(
            machineId = MACHINE,
            offline = offline,
            spaces = listOf(web),
            resolutions = resolution?.let { mapOf(SPACE to it) }.orEmpty(),
        )

    /** The connected machine `m1`, named `build`. */
    private fun machines(): ScriptedMachines {
        val machines = ScriptedMachines()
        machines.put(Machine(id = MACHINE, name = "build", host = "build.lan", port = 22, user = "ci"), ConnectionState.Connected(testHello()))
        return machines
    }

    /**
     * The view model for the space, over the given fixed data.
     *
     * @param groups the groups the spaces repository reports.
     * @param agents the agents repository.
     * @param installed the installed agents of each machine.
     * @return the view model.
     */
    private fun model(
        groups: List<MachineSpaces>,
        agents: AgentsRepository = ScriptedAgents(emptyList()),
        installed: Map<String, List<KnownAgent>> = emptyMap(),
    ): SpaceDetailViewModel = SpaceDetailViewModel(
        machineId = MACHINE,
        spaceId = SPACE,
        spaces = StaticSpaces(groups),
        agents = agents,
        machines = machines(),
        installed = StaticInstalled(installed),
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
     * New agent is off while the machine is offline, and the picker does not open from it: the FAB offline check.
     */
    @Test
    fun theNewAgentIsOffWhileTheMachineIsOfflineAndThePickerDoesNotOpen() {
        val vm = model(listOf(group(offline = true)))

        assertFalse(vm.state.value.newAgent.enabled)
        assertEquals(NewAgentBlock.MachineOffline, vm.state.value.newAgent.block)

        vm.openPicker()
        assertNull("no picker from an offline FAB", vm.state.value.picker)
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

    private companion object {
        const val MACHINE = "m1"
        const val SPACE = "sp_web"
    }
}
