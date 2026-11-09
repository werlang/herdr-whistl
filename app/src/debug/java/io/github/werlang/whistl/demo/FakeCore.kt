package io.github.werlang.whistl.demo

import io.github.werlang.whistl.core.rpc.FramedSession
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.AuthMethod
import io.github.werlang.whistl.data.ClientPublicKey
import io.github.werlang.whistl.data.EditResult
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.ErrorKind
import io.github.werlang.whistl.domain.KnownAgent
import io.github.werlang.whistl.domain.KnownAgents
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
import io.github.werlang.whistl.data.FolderResolution
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.data.UnresolvableReason
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
import io.github.werlang.whistl.domain.TerminalFrame
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.data.TerminalAction
import io.github.werlang.whistl.data.TerminalHandle
import io.github.werlang.whistl.data.TerminalReach
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.TerminalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.werlang.whistl.domain.Rejection
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

    override val installed: InstalledAgentsRepository = FakeInstalled(state)

    override val clientKey: ClientPublicKey = FakeClientKey

    /**
     * Adds a terminal to a demo machine, for the screenshot states that need one. The app never calls it.
     *
     * @param machineId the machine.
     * @param terminal the terminal.
     */
    fun addTerminal(machineId: String, terminal: Terminal) {
        state.addTerminal(machineId, terminal)
    }

    /**
     * Adds a demo machine in a given connection state, for a screenshot state. The app never calls it.
     *
     * @param machine the machine.
     * @param connection the state to show.
     * @param password whether the machine is stored with password login.
     */
    fun addDemoMachine(machine: Machine, connection: ConnectionState, password: Boolean = false) {
        state.addMachine(machine, connection, if (password) AuthMethod.Password else AuthMethod.Key)
    }

    /**
     * Adds a demo space to a machine, for a screenshot state. The app never calls it.
     *
     * @param machineId the machine.
     * @param id the space id.
     * @param name the space name.
     * @param folder the folder.
     */
    fun addDemoSpace(machineId: String, id: String, name: String, folder: String) {
        state.editDocument(machineId) {
            SpacesDocument(it.spaces + SpaceRecord(id = id, name = name, folder = folder, pinned = false, order = it.spaces.size))
        }
    }

    /**
     * Adds a demo agent to a machine for a screenshot state. The app never calls it.
     *
     * @param machineId the machine.
     * @param address the terminal address.
     * @param status the agent status, or `null` for a plain shell.
     * @param cwd the folder.
     * @param name the terminal's name, or `null` for the address.
     */
    fun addAgent(machineId: String, address: String, status: AgentStatus?, cwd: String, name: String? = null) {
        state.addAgent(machineId, address, status, cwd, name = name)
    }

    /**
     * Shows [frame] as the screen of terminal [address], in place of its plain text output. The screenshot tests use it to show
     * colour and the alternate screen; the app never calls it.
     *
     * @param address the terminal address.
     * @param frame the screen to show.
     */
    fun setScreen(address: String, frame: TerminalFrame) {
        state.setFrame(address, frame)
    }

    /**
     * Puts a machine into a connection state, as if its connection had reached it. The screenshot tests use it to show the
     * Machines states; the app never calls it.
     *
     * @param machineId the machine.
     * @param connection the state to show.
     * @throws NoSuchElementException when no machine has that id.
     */
    fun setConnection(machineId: String, connection: ConnectionState) {
        state.setConnection(machineId, connection)
    }

    /**
     * Adds a machine to the demo, in the given state, as a test needs it. The app adds machines through the form.
     *
     * @param machine the machine.
     * @param connection its state.
     */
    fun addMachine(machine: Machine, connection: ConnectionState) {
        state.addMachine(machine, connection)
    }

    override val machines: MachinesRepository = FakeMachines(state)
    override val agents: AgentsRepository = FakeAgents(state)
    override val spaces: SpacesRepository = FakeSpaces(state)
    override val terminals: TerminalRepository = FakeTerminals(state)
}

private val MAC_MACHINE = Machine(id = "mac", name = "mac", host = "mac.local", port = 22, user = "demo")
private val LAB_MACHINE = Machine(id = "lab", name = "lab", host = "lab.local", port = 22, user = "demo")
private val BUILD_MACHINE = Machine(id = "build", name = "build", host = "build.lan", port = 22, user = "ci")
private val PI_MACHINE = Machine(id = "pi", name = "pi", host = "pi.local", port = 22, user = "pi")

/** The fixed demo key line the Add machine sheet shows (not a real key). */
private const val DEMO_PUBLIC_KEY: String =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHk2x9Qd7mJ4vR8pL0wN3cT5bYfU1sE6aK2hG9jZ whistl"

/** The host-key fingerprints the demo presents for `unknown.demo` and `changed.demo`. */
private const val DEMO_FINGERPRINT: String = "SHA256:q8V3mZp1kT0rN7wLx2cY4bH6sJ9dF5gA3eU8iO0pQ1w"
private const val DEMO_OLD_FINGERPRINT: String = "SHA256:Tr4nsT0pK9xLm2Qz8wVb5cNe1fGh7jRy3uDa6oIs0kE"

/**
 * The state a newly added demo machine starts in, chosen by its host name so screenshots and manual checks can reach each
 * state. Names with `.demo` are the demo's own; any other host connects.
 *
 * @param host the validated host text.
 * @return the connection state.
 */
private fun scriptedConnection(host: String): ConnectionState = when (host) {
    "unknown.demo" -> ConnectionState.HostKeyUnknown("ssh-ed25519", DEMO_FINGERPRINT)
    "changed.demo" -> ConnectionState.HostKeyChanged("ssh-ed25519", DEMO_OLD_FINGERPRINT, DEMO_FINGERPRINT)
    "auth.demo" -> ConnectionState.Error(ErrorKind.AuthFailed)
    "unreach.demo" -> ConnectionState.Offline
    "slow.demo" -> ConnectionState.Connecting
    "nocore.demo" -> ConnectionState.Error(ErrorKind.CoreMissing)
    "refuse.demo" -> ConnectionState.Error(ErrorKind.ConnectionRefused)
    else -> ConnectionState.Connected(DEMO_HELLO)
}

/** The demo's public key, the same line for every visit. */
private object FakeClientKey : ClientPublicKey {
    override fun publicKeyLine(): String = DEMO_PUBLIC_KEY
}

/** The installed agents of the demo machines. */
private class FakeInstalled(state: FakeState) : InstalledAgentsRepository {
    override val installed: StateFlow<Map<String, List<KnownAgent>>> = state.installedFlow.asStateFlow()
}

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
    private val auths = LinkedHashMap<String, AuthMethod>()
    private val documents = LinkedHashMap<String, SpacesDocument>()
    private val terminals = LinkedHashMap<String, List<Terminal>>()
    private val outputs = LinkedHashMap<String, MutableList<String>>()

    /** Screens set by the screenshot tests, by address: shown in place of the plain text output. */
    private val frames = LinkedHashMap<String, TerminalFrame>()
    private var machineCounter = 0
    private var spaceCounter = 0L
    private var startCounter = 0

    /** The detected agents per machine: a connected machine's list, and an offline machine's last known list. */
    private val installedByMachine = LinkedHashMap<String, List<KnownAgent>>()
    val installedFlow = MutableStateFlow<Map<String, List<KnownAgent>>>(emptyMap())

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
        addMachine(BUILD_MACHINE, ConnectionState.Connecting)
        addMachine(PI_MACHINE, ConnectionState.Error(ErrorKind.AuthFailed))
        installedByMachine[MAC_MACHINE.id] = listOf(KnownAgents.table[0], KnownAgents.table[1], KnownAgents.table[3])
        installedByMachine[LAB_MACHINE.id] = listOf(KnownAgents.table[0])
        installedFlow.value = installedByMachine.toMap()
    }

    /** Replaces a machine's connection state and publishes. */
    @Synchronized
    fun setConnection(id: String, connection: ConnectionState) {
        val flow = connections[id] ?: throw NoSuchElementException("no machine with id $id")
        flow.value = connection
        publish()
    }

    /** Adds a machine with the given connection state, and publishes. */
    @Synchronized
    fun addMachine(machine: Machine, connection: ConnectionState = ConnectionState.Offline, auth: AuthMethod = AuthMethod.Key) {
        machineList.value = machineList.value + machine
        connections[machine.id] = MutableStateFlow(connection)
        auths[machine.id] = auth
        publish()
    }

    /** The login method a machine was stored with. */
    @Synchronized
    fun authOf(id: String): AuthMethod = auths[id] ?: throw NoSuchElementException("no machine with id $id")

    /**
     * Replaces a machine's settings in place: same id, same position in the list, same connection flow (phase 7 P2, demo).
     *
     * @param machine the edited machine.
     * @param auth its login method.
     */
    @Synchronized
    fun replaceMachine(machine: Machine, auth: AuthMethod) {
        if (machine.id !in connections) throw NoSuchElementException("no machine with id ${machine.id}")
        machineList.value = machineList.value.map { if (it.id == machine.id) machine else it }
        auths[machine.id] = auth
        publish()
    }

    /**
     * Sets a machine's state if the machine still exists: a background attempt may outlive a removed machine.
     *
     * @param id the machine.
     * @param connection the state to show.
     */
    @Synchronized
    fun setConnectionIfKnown(id: String, connection: ConnectionState) {
        connections[id]?.let { flow ->
            flow.value = connection
            publish()
        }
    }

    /** Removes a machine with everything it held, and publishes. */
    @Synchronized
    fun removeMachine(id: String) {
        machineList.value = machineList.value.filterNot { it.id == id }
        connections.remove(id)
        auths.remove(id)
        documents.remove(id)
        terminals.remove(id)
        installedByMachine.remove(id)
        installedFlow.value = installedByMachine.toMap()
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

    /** The host a machine was added with, for the demo's scripted outcomes. */
    @Synchronized
    fun hostOf(id: String): String = machineList.value.firstOrNull { it.id == id }?.host.orEmpty()

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

    /** Adds a terminal to a machine, for a screenshot state that needs one (phase 7 r6, row 11). */
    @Synchronized
    fun addTerminal(id: String, terminal: Terminal) {
        terminals[id] = terminals[id].orEmpty() + terminal
        publish()
    }

    /** Adds a demo agent to a machine, built the way the demo builds its own (phase 7 r6, row 11). */
    fun addAgent(
        id: String,
        address: String,
        status: AgentStatus?,
        cwd: String,
        state: TerminalState = TerminalState.Live,
        name: String? = null,
    ) {
        addTerminal(id, demoTerminal(address, status, cwd, state = state, name = name))
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

    /** The screen set for [address], or `null` when the plain text output is shown. */
    @Synchronized
    fun frame(address: String): TerminalFrame? = frames[address]

    /** Sets the screen of [address]. */
    @Synchronized
    fun setFrame(address: String, frame: TerminalFrame) {
        frames[address] = frame
    }

    /** Appends a line to a terminal's demo output. */
    @Synchronized
    fun append(address: String, line: String) {
        outputs.getOrPut(address) { mutableListOf() } += line
    }

    /**
     * Starts a demo terminal in a space: a known agent with its argv, or the demo machine's shell. Like a started agent on a
     * real machine, it is labelled with the space and joins the agents. Nothing runs and nothing is sent anywhere.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @param choice the agent or the shell.
     * @return the start result.
     */
    @Synchronized
    fun startTerminal(machineId: String, spaceId: String, choice: StartChoice): StartResult {
        if (!isConnected(machineId)) return StartResult.Offline
        val space = documents[machineId]?.spaces?.firstOrNull { it.id == spaceId } ?: return StartResult.UnknownSpace
        val resolution = demoResolution(space.folder)
        if (resolution is FolderResolution.Unresolvable) return StartResult.FolderUnavailable(resolution.reason)
        val argv = when (choice) {
            is StartChoice.Known -> choice.agent.argv
            StartChoice.Terminal -> listOf("/bin/zsh", "-l")
        }
        val address = "t-new-${++startCounter}"
        val known = choice as? StartChoice.Known
        val started = demoTerminal(
            address = address,
            status = if (known != null) AgentStatus.Working else null,
            cwd = space.folder,
            labels = mapOf(Agent.SPACE_LABEL to spaceId),
            name = known?.agent?.displayName ?: "shell",
        ).copy(argv = argv)
        terminals[machineId] = terminals[machineId].orEmpty() + started
        outputs[address] = mutableListOf("demo: started ${argv.first()}")
        publish()
        return StartResult.Started(address)
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
            val spaces = sortSpaces(documents[machine.id]?.spaces.orEmpty())
            MachineSpaces(
                machineId = machine.id,
                offline = !isConnected(machine.id),
                spaces = spaces,
                resolutions = spaces.associate { it.id to demoResolution(it.folder) },
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

/** How long the demo's first attempt takes, so the Add sheet shows its connecting stage before the outcome. */
private const val DEMO_ATTEMPT_MS: Long = 400L

/** The machines of the demo. Adding one validates it the way the real repository does; no machine is contacted. */
private class FakeMachines(private val state: FakeState) : MachinesRepository {
    /** Runs the demo's first attempt of an added machine in the background, as the real connection does. */
    private val attempts = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun machines(): StateFlow<List<Machine>> = state.machineList.asStateFlow()

    override fun observe(id: String): StateFlow<ConnectionState> = state.connectionOf(id)

    override suspend fun add(draft: NewMachine, password: CharArray?): AddResult {
        // The demo keeps no password: the parameter is accepted and dropped. The first state is Offline, as a new machine's
        // is; the attempt then starts (Connecting) and ends in the scripted state, as the real connection does.
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
        state.addMachine(machine, ConnectionState.Offline, if (password == null) AuthMethod.Key else AuthMethod.Password)
        attempts.launch {
            state.setConnectionIfKnown(machine.id, ConnectionState.Connecting)
            delay(DEMO_ATTEMPT_MS)
            state.setConnectionIfKnown(machine.id, scriptedConnection(host))
        }
        return AddResult.Added(machine)
    }

    override suspend fun remove(id: String) {
        state.removeMachine(id)
    }

    override suspend fun acquire(id: String): MachineLease {
        state.requireMachine(id)
        return FakeLease(id)
    }

    override suspend fun reconnect(id: String) {
        val current = state.connectionOf(id).value
        if (current !is ConnectionState.Offline && current !is ConnectionState.Error) return
        state.setConnection(id, ConnectionState.Connecting)
        state.setConnection(id, scriptedConnection(state.hostOf(id)))
    }

    override fun authOf(id: String): AuthMethod = state.authOf(id)

    /**
     * Edit machine in the demo, following the repository's rules as far as a fake can (phase 7 P2): a name-only edit only
     * changes the stored machine; a user, port or login change reconnects, unless a host-key decision is pending; a host change
     * starts a new connection to the new host, so its scripted outcome (for example the unknown key) shows.
     */
    override suspend fun edit(id: String, draft: NewMachine, auth: AuthMethod, password: CharArray?): EditResult {
        val before = machines().value.firstOrNull { it.id == id } ?: throw NoSuchElementException("no machine with id $id")
        if (auth == AuthMethod.Password && password != null && password.isEmpty()) {
            return EditResult.Rejected(MachineField.Password, Rejection.EMPTY)
        }
        if (auth == AuthMethod.Password && password == null && state.authOf(id) != AuthMethod.Password) {
            return EditResult.Rejected(MachineField.Password, Rejection.EMPTY)
        }
        val host = when (val r = validateHost(draft.host)) {
            is Validation.Valid -> r.value.text
            is Validation.Invalid -> return EditResult.Rejected(MachineField.Host, r.reason)
        }
        val port = when (val r = validatePort(draft.port)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return EditResult.Rejected(MachineField.Port, r.reason)
        }
        val user = when (val r = validateUser(draft.user)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return EditResult.Rejected(MachineField.User, r.reason)
        }
        val name = when (val r = validateMachineName(draft.name.ifBlank { host.substringBefore('.') })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return EditResult.Rejected(MachineField.Name, r.reason)
        }
        val machine = Machine(id = id, name = name, host = host, port = port, user = user)
        val previousAuth = state.authOf(id)
        state.replaceMachine(machine, auth)
        val current = state.connectionOf(id).value
        when {
            host != before.host -> {
                state.setConnection(id, ConnectionState.Connecting)
                state.setConnection(id, scriptedConnection(host))
            }

            current is ConnectionState.HostKeyUnknown || current is ConnectionState.HostKeyChanged -> Unit

            before.user != user || before.port != port || (auth == AuthMethod.Password && password != null) ||
                auth != previousAuth -> {
                state.setConnection(id, ConnectionState.Connecting)
                state.setConnection(id, scriptedConnection(host))
            }

            else -> Unit
        }
        return EditResult.Saved(machine)
    }

    override suspend fun confirmHostKey(id: String): ConfirmResult = when (state.connectionOf(id).value) {
        is ConnectionState.HostKeyUnknown -> {
            state.setConnection(id, ConnectionState.Connected(DEMO_HELLO))
            ConfirmResult.Trusted
        }

        is ConnectionState.HostKeyChanged -> ConfirmResult.Blocked
        else -> ConfirmResult.NothingPending
    }
}

/** A lease that holds nothing: the demo has no live session to keep open. */
private class FakeLease(override val machineId: String) : MachineLease {
    override fun client(): HcClient? = null

    override suspend fun openStream(): FramedSession = throw TransportLost("the demo has no streams")

    override suspend fun release() {}

    override fun reportLost(client: HcClient, cause: TransportLost) {}
}

/** The agents of the demo: those on connected machines, with the space each one matches. */
private class FakeAgents(private val state: FakeState) : AgentsRepository {
    override val agents: StateFlow<List<MachineAgent>> = state.agentFlow.asStateFlow()

    override val blockedCount: StateFlow<Int> = state.blockedFlow.asStateFlow()

    override suspend fun start(machineId: String, spaceId: String, choice: StartChoice): StartResult =
        state.startTerminal(machineId, spaceId, choice)
}

/**
 * The folder resolution the demo gives a space's folder. `/home/demo/docs` does not exist on the demo machine, so its row shows
 * the typed reason; every other folder resolves to itself.
 *
 * @param folder the folder as stored.
 * @return the resolution.
 */
private fun demoResolution(folder: String): FolderResolution =
    when (folder) {
        DEMO_MISSING_FOLDER -> FolderResolution.Unresolvable(UnresolvableReason.NotFound)
        DEMO_UNREADABLE_FOLDER -> FolderResolution.Unresolvable(UnresolvableReason.HomeUnreadable)
        else -> FolderResolution.Resolved(folder)
    }

/** The folder of the demo's `docs` space, which the demo treats as missing on its machine. */
private const val DEMO_MISSING_FOLDER: String = "/home/demo/docs"

/** A folder whose home could not be read on its machine, for the unreadable-home state (phase 7 r6, row 11). */
private const val DEMO_UNREADABLE_FOLDER: String = "/home/demo/unreadable"

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

    override fun resize(cols: Int, rows: Int) {}

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
        val frame = state.frame(address) ?: TerminalFrame.plain(state.output(address).joinToString("\n"))
        return TerminalScreen(frame = frame, reach = reach)
    }

    private fun refresh() {
        output.value = read()
    }
}
