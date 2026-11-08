package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ClientPublicKey
import io.github.werlang.whistl.data.ConfirmResult
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineLease
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.NewMachine
import io.github.werlang.whistl.data.StartChoice
import io.github.werlang.whistl.data.StartResult
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.KnownAgent
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.ui.machines.ClipboardWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A hello result the tests use for a connected machine. */
fun testHello(): HelloResult = HelloResult(
    coreId = "test",
    coreName = "test",
    features = emptyList(),
    generation = 1,
    methods = emptyList(),
    version = "0.1.18",
)

/**
 * A machines repository whose states the test sets, and that records every call. It stands in for the real repository where a
 * test needs a state the real connection takes long to reach; the real repository is covered by its own tests.
 *
 * The add, host-key and reconnect calls follow the real repository's visible results: a trusted unknown key connects, a
 * changed key is refused, and an added machine starts offline.
 */
class ScriptedMachines : MachinesRepository {
    private val list = MutableStateFlow<List<Machine>>(emptyList())
    private val states = LinkedHashMap<String, MutableStateFlow<ConnectionState>>()
    private var counter = 0

    /** The drafts [add] received, in order. */
    val added = mutableListOf<NewMachine>()

    /** The password each [add] received (`null` in key mode), in order. */
    val passwords = mutableListOf<CharArray?>()

    /** The ids removed, in order. */
    val removed = mutableListOf<String>()

    /** The ids reconnected, in order. */
    val reconnected = mutableListOf<String>()

    /** The ids whose host key was trusted, in order. */
    val trusted = mutableListOf<String>()

    /** The client a lease returns while the machine is connected; `null` for none. */
    var leaseClient: HcClient? = null

    /** What [add] answers; `null` for the default, which adds the machine offline. */
    var addAnswer: ((NewMachine) -> AddResult)? = null

    /**
     * Puts a machine in the list, in a state.
     *
     * @param machine the machine.
     * @param state its state.
     */
    fun put(machine: Machine, state: ConnectionState) {
        list.update { it + machine }
        states[machine.id] = MutableStateFlow(state)
    }

    /**
     * Sets a machine's state.
     *
     * @param id the machine.
     * @param state the state to show.
     */
    fun setState(id: String, state: ConnectionState) {
        states.getValue(id).value = state
    }

    override fun machines(): StateFlow<List<Machine>> = list

    override fun observe(id: String): StateFlow<ConnectionState> =
        states[id] ?: throw NoSuchElementException("no machine with id $id")

    override suspend fun add(draft: NewMachine, password: CharArray?): AddResult {
        added += draft
        passwords += password
        addAnswer?.let { return it(draft) }
        val machine = Machine(
            id = "s${++counter}",
            name = draft.name,
            host = draft.host,
            port = draft.port,
            user = draft.user,
        )
        put(machine, ConnectionState.Offline)
        return AddResult.Added(machine)
    }

    override suspend fun remove(id: String) {
        removed += id
        list.update { current -> current.filterNot { it.id == id } }
        states.remove(id)
    }

    override suspend fun acquire(id: String): MachineLease {
        observe(id)
        return object : MachineLease {
            override val machineId: String = id

            override fun client(): HcClient? = leaseClient

            override suspend fun release() {}

            override fun reportLost(client: HcClient, cause: TransportLost) {}
        }
    }

    override suspend fun confirmHostKey(id: String): ConfirmResult {
        trusted += id
        return when (observe(id).value) {
            is ConnectionState.HostKeyUnknown -> {
                setState(id, ConnectionState.Connected(testHello()))
                ConfirmResult.Trusted
            }

            is ConnectionState.HostKeyChanged -> ConfirmResult.Blocked
            else -> ConfirmResult.NothingPending
        }
    }

    override suspend fun reconnect(id: String) {
        reconnected += id
    }
}

/** Agents that never change: the list the test gives. */
class StaticAgents(initial: List<MachineAgent> = emptyList()) : AgentsRepository {
    override val agents: StateFlow<List<MachineAgent>> = MutableStateFlow(initial)

    override val blockedCount: StateFlow<Int> = MutableStateFlow(0)

    /** Starting is not part of these tests; a test that needs it uses the real repository. */
    override suspend fun start(machineId: String, spaceId: String, choice: StartChoice): StartResult = StartResult.Offline
}

/** Installed agents that never change: the map the test gives. */
class StaticInstalled(initial: Map<String, List<KnownAgent>> = emptyMap()) : InstalledAgentsRepository {
    override val installed: StateFlow<Map<String, List<KnownAgent>>> = MutableStateFlow(initial)
}

/** A clipboard that records what was copied. */
class RecordingClipboard : ClipboardWriter {
    /** The texts copied, in order. */
    val copied = mutableListOf<String>()

    override fun copy(text: String) {
        copied += text
    }
}

/** This device's public key, fixed for the tests. */
object FixedClientKey : ClientPublicKey {
    /** The key line the tests expect the sheet to show. */
    const val LINE: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFixedTestKey whistl"

    override fun publicKeyLine(): String = LINE
}
