package io.github.werlang.whistl.demo

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ConfirmResult
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.DEFAULT_FOLDER
import io.github.werlang.whistl.data.DemoCore
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineField
import io.github.werlang.whistl.data.MachineLease
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.NewMachine
import io.github.werlang.whistl.data.SpaceField
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.SpacesDocument
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.defaultSpaceName
import io.github.werlang.whistl.data.hasSpace
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.data.mapSpace
import io.github.werlang.whistl.data.matchSpace
import io.github.werlang.whistl.data.moved
import io.github.werlang.whistl.data.nextOrder
import io.github.werlang.whistl.data.sortSpaces
import io.github.werlang.whistl.data.withSpace
import io.github.werlang.whistl.data.without
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.data.TerminalAction
import io.github.werlang.whistl.data.TerminalHandle
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.werlang.whistl.domain.Validation
import io.github.werlang.whistl.domain.validateFolder
import io.github.werlang.whistl.domain.validateHost
import io.github.werlang.whistl.domain.validateMachineName
import io.github.werlang.whistl.domain.validatePort
import io.github.werlang.whistl.domain.validateSpaceName
import io.github.werlang.whistl.domain.validateUser

/**
 * An in-memory [DemoCore] for the debug build only. It is seeded like the design prototype: a connected `mac` with spaces
 * and agents in every status, an offline `lab` whose spaces come from its cache, and agents with no space. The `mac` also
 * holds a finished agent whose last status was `blocked`; the badge does not count it.
 *
 * Edits change the in-memory copy and nothing else: no machine is contacted and nothing is saved. A killed agent leaves
 * the list at once; the real core keeps a finished terminal until it is removed.
 */
class FakeCore : DemoCore {
    private val state = FakeState()

    override val machines: MachinesRepository = FakeMachines(state)
    override val agents: AgentsRepository = FakeAgents(state)
    override val spaces: SpacesRepository = FakeSpaces(state)
    override val terminals: TerminalRepository = FakeTerminals(state)
}

private val MAC_MACHINE = Machine(id = "mac", name = "mac", host = "mac.local", port = 22, user = "demo")
private val LAB_MACHINE = Machine(id = "lab", name = "lab", host = "lab.local", port = 22, user = "demo")

private val DEMO_HELLO = HelloResult(
    coreId = "demo",
    coreName = "demo",
    features = emptyList(),
    generation = 1,
    methods = emptyList(),
    version = "0.1.18",
)

/**
 * The shared in-memory data of one [FakeCore]. Each mutation is synchronized and ends with a publish, which recomputes
 * the three flows the screens read, so the repositories always agree.
 */
private class FakeState {
    /** The machines, in the order they were added. */
    val machineList = MutableStateFlow<List<Machine>>(emptyList())

    /** Spaces per machine, as the spaces screen reads them. */
    val groupFlow = MutableStateFlow<List<MachineSpaces>>(emptyList())

    /** Agents on connected machines, as the agents screen reads them. */
    val agentFlow = MutableStateFlow<List<MachineAgent>>(emptyList())

    /** Agents waiting for the user (live and blocked): the badge count. */
    val blockedFlow = MutableStateFlow(0)

    private val connections = LinkedHashMap<String, MutableStateFlow<ConnectionState>>()
    private val documents = LinkedHashMap<String, SpacesDocument>()
    private val terminals = LinkedHashMap<String, List<Terminal>>()
    private val outputs = LinkedHashMap<String, MutableList<String>>()
    private var machineCounter = 0
    private var spaceCounter = 0L

    init {
        documents[MAC_MACHINE.id] = SpacesDocument(
            listOf(
                SpaceRecord(id = "sp_api", name = "api", folder = "/home/demo/api", pinned = true, order = 0),
                SpaceRecord(id = "sp_web", name = "web", folder = "/home/demo/web", pinned = false, order = 0),
                SpaceRecord(id = "sp_docs", name = "docs", folder = "/home/demo/docs", pinned = false, order = 1),
            ),
        )
        documents[LAB_MACHINE.id] = SpacesDocument(
            listOf(SpaceRecord(id = "sp_lab", name = "lab", folder = "/srv/lab", pinned = false, order = 0)),
        )
        terminals[MAC_MACHINE.id] = listOf(
            demoTerminal("t-idle", AgentStatus.Idle, cwd = "/home/demo/web", name = "opencode"),
            demoTerminal("t-working", AgentStatus.Working, cwd = "/home/demo/elsewhere", labels = mapOf(Agent.SPACE_LABEL to "sp_api"), name = "claude"),
            demoTerminal("t-done", AgentStatus.Done, cwd = "/home/demo/api", name = "claude"),
            demoTerminal("t-blocked", AgentStatus.Blocked, cwd = "/home/demo/web", name = "codex"),
            demoTerminal("t-error", AgentStatus.Error, cwd = "/tmp", name = "gemini"),
            demoTerminal("t-plain", null, cwd = "/home/demo/notes", name = "shell"),
            demoTerminal("t-finished", AgentStatus.Blocked, cwd = "/home/demo/docs", state = TerminalState.Finished, name = "codex"),
        )
        outputs["t-working"] = mutableListOf(
            "> update the compose file to 10.10",
            "● Reading docker-compose.yml",
            "  - image: jellyfin/jellyfin:10.9.11",
            "  + image: jellyfin/jellyfin:10.10.3",
            "● Running docker compose pull jellyfin",
        )
        outputs["t-blocked"] = mutableListOf(
            "> review the restore script",
            "● Read restore.sh (84 lines)",
            "Run ./restore.sh --dry-run?",
            "1. Yes",
            "2. No",
        )
        outputs["t-idle"] = mutableListOf(
            "demo@lab:/home/demo/web$ uptime",
            " 14:02:11 up 41 days,  3:12,  load average: 0.31, 0.28, 0.25",
        )
        outputs["t-done"] = mutableListOf(
            "> review the README",
            "● Read README.md and CONTRIBUTING.md",
            "✓ Fixed 3 broken links and the install section.",
        )
        addMachine(MAC_MACHINE, ConnectionState.Connected(DEMO_HELLO))
        addMachine(LAB_MACHINE, ConnectionState.Offline)
    }

    /** Adds a machine with the given connection state, and publishes. */
    @Synchronized
    fun addMachine(machine: Machine, connection: ConnectionState = ConnectionState.Offline) {
        machineList.value = machineList.value + machine
        connections[machine.id] = MutableStateFlow(connection)
        publish()
    }

    /** Removes a machine with everything it held, and publishes. */
    @Synchronized
    fun removeMachine(id: String) {
        machineList.value = machineList.value.filterNot { it.id == id }
        connections.remove(id)
        documents.remove(id)
        terminals.remove(id)
        publish()
    }

    /**
     * The connection state flow of a machine.
     *
     * @throws NoSuchElementException when no machine has that id.
     */
    @Synchronized
    fun connectionOf(id: String): StateFlow<ConnectionState> =
        (connections[id] ?: throw NoSuchElementException("no machine with id $id")).asStateFlow()

    /**
     * Checks that a machine exists.
     *
     * @throws NoSuchElementException when no machine has that id.
     */
    @Synchronized
    fun requireMachine(id: String) {
        if (id !in connections) throw NoSuchElementException("no machine with id $id")
    }

    /** Whether the machine is connected: only a connected machine accepts writes and shows agents. */
    @Synchronized
    fun isConnected(id: String): Boolean = connections[id]?.value is ConnectionState.Connected

    /** The machine's spaces document as it is held, or `null` for an unknown machine. */
    @Synchronized
    fun document(id: String): SpacesDocument? = documents[id]

    /** Applies one edit to the machine's document, and publishes. */
    @Synchronized
    fun editDocument(id: String, change: (SpacesDocument) -> SpacesDocument) {
        documents[id] = change(documents[id] ?: SpacesDocument.empty())
        publish()
    }

    /** Removes the terminals of a machine that match [predicate], and publishes. */
    @Synchronized
    fun dropTerminals(id: String, predicate: (Terminal) -> Boolean) {
        terminals[id] = terminals[id].orEmpty().filterNot(predicate)
        publish()
    }

    /** The terminal with [address] on machine [id], or `null`. */
    @Synchronized
    fun terminal(id: String, address: String): Terminal? = terminals[id].orEmpty().firstOrNull { it.address == address }

    /** The output lines held for a terminal, in order. Static demo text, never sent anywhere. */
    @Synchronized
    fun output(address: String): List<String> = outputs[address].orEmpty()

    /** Appends a line to a terminal's demo output. */
    @Synchronized
    fun append(address: String, line: String) {
        outputs.getOrPut(address) { mutableListOf() } += line
    }

    /** A new machine id, for a machine added in the demo. */
    @Synchronized
    fun nextMachineId(): String = "demo-${++machineCounter}"

    /** A new space id in the `sp_` + 16 lowercase hex form. */
    @Synchronized
    fun nextSpaceId(): String = "sp_" + (++spaceCounter).toString(16).padStart(16, '0')

    /** Recomputes the three flows from the held data. Called after every mutation. */
    private fun publish() {
        groupFlow.value = machineList.value.map { machine ->
            MachineSpaces(
                machineId = machine.id,
                offline = !isConnected(machine.id),
                spaces = sortSpaces(documents[machine.id]?.spaces.orEmpty()),
            )
        }
        val agents = machineList.value.filter { isConnected(it.id) }.flatMap { machine ->
            val spaces = documents[machine.id]?.spaces.orEmpty()
            terminals[machine.id].orEmpty().map { MachineAgent(machine.id, Agent(it), matchSpace(it, spaces)) }
        }
        agentFlow.value = agents
        blockedFlow.value = agents.count { it.agent.terminal.isWaitingForUser() }
    }

    private companion object {
        /** A demo terminal, live unless [state] says it finished. */
        fun demoTerminal(
            address: String,
            status: AgentStatus?,
            cwd: String,
            labels: Map<String, String> = emptyMap(),
            state: TerminalState = TerminalState.Live,
            name: String? = null,
        ): Terminal = Terminal(
            address = address,
            name = name,
            argv = emptyList(),
            cwd = cwd,
            labels = labels,
            state = state,
            status = status,
            turn = 1,
            exitCode = null,
            createdMs = null,
            finishedMs = null,
            title = null,
            pid = null,
            signal = null,
            reason = null,
            keep = false,
        )
    }
}

/** The machines of the demo. Adding one validates it the way the real repository does; no machine is contacted. */
private class FakeMachines(private val state: FakeState) : MachinesRepository {
    override fun machines(): StateFlow<List<Machine>> = state.machineList.asStateFlow()

    override fun observe(id: String): StateFlow<ConnectionState> = state.connectionOf(id)

    override suspend fun add(draft: NewMachine, password: CharArray?): AddResult {
        // The demo keeps no password: the parameter is accepted and dropped.
        val host = when (val r = validateHost(draft.host)) {
            is Validation.Valid -> r.value.text
            is Validation.Invalid -> return AddResult.Rejected(MachineField.Host, r.reason)
        }
        val port = when (val r = validatePort(draft.port)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return AddResult.Rejected(MachineField.Port, r.reason)
        }
        val user = when (val r = validateUser(draft.user)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return AddResult.Rejected(MachineField.User, r.reason)
        }
        val name = when (val r = validateMachineName(draft.name.ifBlank { host.substringBefore('.') })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return AddResult.Rejected(MachineField.Name, r.reason)
        }
        val machine = Machine(id = state.nextMachineId(), name = name, host = host, port = port, user = user)
        state.addMachine(machine)
        return AddResult.Added(machine)
    }

    override suspend fun remove(id: String) {
        state.removeMachine(id)
    }

    override suspend fun acquire(id: String): MachineLease {
        state.requireMachine(id)
        return FakeLease(id)
    }

    override suspend fun confirmHostKey(id: String): ConfirmResult = ConfirmResult.NothingPending
}

/** A lease that holds nothing: the demo has no live session to keep open. */
private class FakeLease(override val machineId: String) : MachineLease {
    override fun client(): HcClient? = null

    override suspend fun release() {}

    override fun reportLost(client: HcClient, cause: TransportLost) {}
}

/** The agents of the demo: those on connected machines, with the space each one matches. */
private class FakeAgents(state: FakeState) : AgentsRepository {
    override val agents: StateFlow<List<MachineAgent>> = state.agentFlow.asStateFlow()

    override val blockedCount: StateFlow<Int> = state.blockedFlow.asStateFlow()
}

/**
 * The spaces of the demo. Edits follow the real repository's rules: an unknown space is reported first, then a write to an
 * offline machine is refused. A close kills the live agents that match the space, then removes it.
 */
private class FakeSpaces(private val state: FakeState) : SpacesRepository {
    override fun groups(): StateFlow<List<MachineSpaces>> = state.groupFlow.asStateFlow()

    override suspend fun create(machineId: String, folder: String, name: String): SpaceResult {
        val path = when (val r = validateFolder(folder.ifBlank { DEFAULT_FOLDER })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Folder, r.reason)
        }
        val spaceName = when (val r = validateSpaceName(name.ifBlank { defaultSpaceName(path) })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Name, r.reason)
        }
        if (!state.isConnected(machineId)) return SpaceResult.MachineOffline
        val id = state.nextSpaceId()
        state.editDocument(machineId) { doc ->
            doc.withSpace(SpaceRecord(id = id, name = spaceName, folder = path, pinned = false, order = doc.nextOrder()))
        }
        return SpaceResult.Done(id)
    }

    override suspend fun rename(machineId: String, spaceId: String, name: String): SpaceResult {
        val spaceName = when (val r = validateSpaceName(name)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Name, r.reason)
        }
        return change(machineId, spaceId) { doc -> doc.mapSpace(spaceId) { it.copy(name = spaceName) } }
    }

    override suspend fun setPinned(machineId: String, spaceId: String, pinned: Boolean): SpaceResult =
        change(machineId, spaceId) { doc -> doc.mapSpace(spaceId) { it.copy(pinned = pinned) } }

    override suspend fun moveUp(machineId: String, spaceId: String): SpaceResult =
        change(machineId, spaceId) { doc -> doc.moved(spaceId, up = true) }

    override suspend fun moveDown(machineId: String, spaceId: String): SpaceResult =
        change(machineId, spaceId) { doc -> doc.moved(spaceId, up = false) }

    override suspend fun close(machineId: String, spaceId: String): SpaceResult {
        val known = state.document(machineId) ?: return SpaceResult.UnknownSpace(spaceId)
        if (!known.hasSpace(spaceId)) return SpaceResult.UnknownSpace(spaceId)
        if (!state.isConnected(machineId)) return SpaceResult.MachineOffline
        val spaces = known.spaces
        state.dropTerminals(machineId) { it.state == TerminalState.Live && matchSpace(it, spaces)?.id == spaceId }
        state.editDocument(machineId) { doc -> doc.without(spaceId) }
        return SpaceResult.Done(spaceId)
    }

    /** Applies one edit to an existing space, after the unknown-space and offline checks. */
    private fun change(machineId: String, spaceId: String, transform: (SpacesDocument) -> SpacesDocument): SpaceResult {
        val known = state.document(machineId) ?: return SpaceResult.UnknownSpace(spaceId)
        if (!known.hasSpace(spaceId)) return SpaceResult.UnknownSpace(spaceId)
        if (!state.isConnected(machineId)) return SpaceResult.MachineOffline
        state.editDocument(machineId, transform)
        return SpaceResult.Done(spaceId)
    }
}

/**
 * The terminals of the demo. Output is the static text the demo data holds. A send to a blocked program is refused unless
 * forced, as hc refuses it; no program runs and nothing is sent to any machine.
 */
private class FakeTerminals(private val state: FakeState) : TerminalRepository {
    override suspend fun open(machineId: String, address: String): TerminalHandle {
        state.requireMachine(machineId)
        return FakeTerminalHandle(state, machineId, address)
    }
}

/** One demo terminal, read from [FakeState]. Actions change only the demo data. */
private class FakeTerminalHandle(
    private val state: FakeState,
    private val machineId: String,
    private val address: String,
) : TerminalHandle {
    private val output = MutableStateFlow(read())

    override val screen: StateFlow<TerminalScreen> = output.asStateFlow()

    override suspend fun send(text: String, force: Boolean): TerminalAction {
        gate()?.let { return it }
        val terminal = state.terminal(machineId, address) ?: return TerminalAction.Gone
        if (terminal.status == AgentStatus.Blocked && !force) return TerminalAction.Blocked
        state.append(address, "> $text")
        refresh()
        return TerminalAction.Done
    }

    override suspend fun keys(names: List<String>): TerminalAction {
        gate()?.let { return it }
        return TerminalAction.Done
    }

    override suspend fun interrupt(): TerminalAction {
        gate()?.let { return it }
        state.append(address, "^C")
        refresh()
        return TerminalAction.Done
    }

    override suspend fun stopAndRemove(): TerminalAction {
        gate()?.let { return it }
        state.dropTerminals(machineId) { it.address == address }
        refresh()
        return TerminalAction.Done
    }

    override fun close() {}

    /** Offline or gone, as the action's result; `null` when the action may run. */
    private fun gate(): TerminalAction? = when {
        !state.isConnected(machineId) -> TerminalAction.Offline
        state.terminal(machineId, address) == null -> TerminalAction.Gone
        else -> null
    }

    private fun read(): TerminalScreen {
        val reach = when {
            !state.isConnected(machineId) -> TerminalReach.Offline
            state.terminal(machineId, address) == null -> TerminalReach.Gone
            else -> TerminalReach.Connected
        }
        return TerminalScreen(text = state.output(address).joinToString("\n"), reach = reach)
    }

    private fun refresh() {
        output.value = read()
    }
}
