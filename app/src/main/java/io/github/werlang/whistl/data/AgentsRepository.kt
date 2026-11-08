package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.EventSubscription
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.SpawnFailed
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.KnownAgent
import io.github.werlang.whistl.domain.Terminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The shell a machine gets when its `SHELL` is empty or not absolute (phase 4b r2, SPEC §2.3). */
const val DEFAULT_SHELL: String = "/bin/sh"

/** The agents on connected machines and the Agents badge count (SPEC §1, §2). */
interface AgentsRepository {
    /** Every agent on a connected machine, each with its machine and its matched space, in machine order. */
    val agents: StateFlow<List<MachineAgent>>

    /**
     * How many agents are waiting for the user: live and `blocked`, by [isWaitingForUser]. A finished agent never counts.
     * This is the Agents badge (SPEC §2); zero hides the badge.
     */
    val blockedCount: StateFlow<Int>

    /**
     * Starts an agent, or the machine's login shell, in a space's folder, labelled as belonging to the space (phase 4b r2,
     * SPEC §2.3). The program is run as an argv list on the machine's live connection; nothing is typed into a shell.
     *
     * @param machineId the machine the space lives on.
     * @param spaceId the space the agent joins.
     * @param choice the known agent to run, or the machine's shell.
     * @return the new terminal's address, or why it did not start.
     */
    suspend fun start(machineId: String, spaceId: String, choice: StartChoice): StartResult
}

/**
 * What a new agent starts with (phase 4b r2, SPEC §2.3): a known agent, run as its own argv, or the machine's login shell.
 */
sealed interface StartChoice {
    /**
     * A known agent from the detection table.
     *
     * @property agent the agent; its argv is run as it is.
     */
    data class Known(val agent: KnownAgent) : StartChoice

    /** The machine's login shell, read from its `SHELL` (SPEC §5). */
    data object Terminal : StartChoice
}

/** The outcome of [AgentsRepository.start]. Every failure is a value, not an exception. */
sealed interface StartResult {
    /**
     * The agent started.
     *
     * @property address the new terminal's address.
     */
    data class Started(val address: String) : StartResult

    /**
     * The space's folder is not resolved on this connection, so nothing was started.
     *
     * @property reason why the folder is unavailable, or `null` when it has not been checked on this device yet.
     */
    data class FolderUnavailable(val reason: UnresolvableReason?) : StartResult

    /** No space with that id is on the machine. */
    data object UnknownSpace : StartResult

    /** The machine could not start the program (hc `spawn_failed`, or another refusal). */
    data object CouldNotStart : StartResult

    /** The machine is not connected, or the connection dropped while starting. */
    data object Offline : StartResult
}

/**
 * The default [AgentsRepository] (round p3r2, SPEC §5).
 *
 * For each machine it holds a lease, so the machine stays connected. While the machine is `Connected` it opens one events
 * subscription (`eventsSubscribe` with no label filter), seeds from the snapshot, and applies each event with
 * [AgentEventReducer]. When the machine leaves `Connected`, the subscription ends and the machine's agents leave the
 * list. A `TransportLost` on the events channel (including the 40 s watchdog) is reported to the session through
 * [MachineLease.reportLost], so the machine goes offline and retries.
 *
 * There is at most one events subscription per machine at a time: a new one starts only after the old one has finished.
 *
 * @param scope the scope for watchers and subscriptions.
 * @param machines the machines and their leases.
 * @param spaces the spaces, used to match each agent to its space.
 * @param documentChanges where `doc` events for `whistl` are announced.
 */
class DefaultAgentsRepository(
    private val scope: CoroutineScope,
    private val machines: MachinesRepository,
    private val spaces: SpacesRepository,
    private val documentChanges: DocumentChanges,
) : AgentsRepository {
    private val terminals = MutableStateFlow<Map<String, Map<String, Terminal>>>(emptyMap())
    private val watchers = ConcurrentHashMap<String, Job>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    /** Failed subscription attempts in a row per machine: the backoff grows with it, and a good snapshot clears it. */
    private val retries = ConcurrentHashMap<String, Int>()

    private companion object {
        const val TAG: String = "AgentsRepository"
    }

    /** One live events subscription: the client it was opened on, and the job that reads it. */
    private class Subscription(val client: HcClient, val job: Job)

    override val agents: StateFlow<List<MachineAgent>> = combine(terminals, spaces.groups(), machines.machines()) {
        byMachine, groups, list ->
        list.flatMap { machine ->
            val group = groups.firstOrNull { it.machineId == machine.id }
            val spacesOfMachine = group?.spaces.orEmpty()
            byMachine[machine.id].orEmpty().values.map { terminal ->
                // Matched by cwd against the resolved path; a space that did not resolve never matches by cwd (SPEC §1).
                val space = matchSpace(terminal, spacesOfMachine) { group?.resolvedPathOf(it) }
                MachineAgent(machine.id, Agent(terminal), space)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val blockedCount: StateFlow<Int> = agents
        .map { list -> list.count { it.agent.terminal.isWaitingForUser() } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch {
            machines.machines().collect { list -> reconcile(list.map { it.id }.toSet()) }
        }
    }

    /** Starts a watcher for each new machine and stops the watcher of each removed one. */
    /** The login shell read for each machine, with the connection it was read on: a new connection reads it again. */
    private val shells = ConcurrentHashMap<String, Pair<HcClient, String>>()

    override suspend fun start(machineId: String, spaceId: String, choice: StartChoice): StartResult {
        val group = spaces.groups().value.firstOrNull { it.machineId == machineId }
        val space = group?.spaces?.firstOrNull { it.id == spaceId } ?: return StartResult.UnknownSpace
        // The folder is checked before any command: an unresolved folder never reaches terminal.run.
        val cwd = when (val resolution = group.resolutionOf(space)) {
            is FolderResolution.Resolved -> resolution.path
            is FolderResolution.Unresolvable -> return StartResult.FolderUnavailable(resolution.reason)
            null -> return StartResult.FolderUnavailable(null)
        }
        val lease = try {
            machines.acquire(machineId)
        } catch (e: NoSuchElementException) {
            return StartResult.Offline
        }
        try {
            val client = lease.client() ?: return StartResult.Offline
            val argv = when (choice) {
                is StartChoice.Known -> choice.agent.argv
                StartChoice.Terminal -> listOf(shellOf(machineId, client), "-l")
            }
            val terminal = client.terminalRun(argv, cwd, mapOf(Agent.SPACE_LABEL to spaceId))
            return StartResult.Started(terminal.address)
        } catch (e: SpawnFailed) {
            return StartResult.CouldNotStart
        } catch (e: TransportLost) {
            return StartResult.Offline
        } catch (e: HcException) {
            return StartResult.CouldNotStart
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    /**
     * The machine's login shell for a connection: read once per connection with `printenv SHELL` (a constant argv), and kept
     * for that connection only. An empty or relative answer, or a failed read, gives `/bin/sh`. A lost connection is rethrown.
     *
     * @param machineId the machine.
     * @param client the live connection the shell is read on.
     * @return an absolute path to a shell.
     */
    private suspend fun shellOf(machineId: String, client: HcClient): String {
        shells[machineId]?.let { (cached, shell) -> if (cached === client) return shell }
        val read = try {
            client.machineExec(listOf("printenv", "SHELL"))
        } catch (e: TransportLost) {
            throw e
        } catch (e: HcException) {
            null
        }
        val answer = read?.takeIf { it.code == 0 }?.stdout?.trim().orEmpty()
        val shell = answer.takeIf { it.startsWith('/') && it.none(Char::isWhitespace) } ?: DEFAULT_SHELL
        shells[machineId] = client to shell
        return shell
    }

    private suspend fun reconcile(ids: Set<String>) {
        for (id in watchers.keys.toList()) {
            if (id !in ids) watchers.remove(id)?.cancelAndJoin()
        }
        for (id in ids) {
            if (!watchers.containsKey(id)) watchers[id] = scope.launch { watch(id) }
        }
    }

    /** Holds the machine's lease and follows its connection state until the watcher is cancelled. */
    private suspend fun watch(id: String) {
        val lease = try {
            machines.acquire(id)
        } catch (e: NoSuchElementException) {
            return
        }
        try {
            machines.observe(id).collect { state ->
                if (state is ConnectionState.Connected) {
                    val client = lease.client()
                    if (client != null) ensureSubscription(id, lease, client)
                } else {
                    endSubscription(id)
                }
            }
        } catch (e: NoSuchElementException) {
            return
        } finally {
            withContext(NonCancellable) {
                endSubscription(id)
                terminals.update { it - id }
                lease.release()
            }
        }
    }

    /** Opens the events subscription on [client] unless one is already running on it. */
    private suspend fun ensureSubscription(id: String, lease: MachineLease, client: HcClient) {
        val current = subscriptions[id]
        if (current != null && current.client === client && current.job.isActive) return
        endSubscription(id)
        check(subscriptions[id] == null) { "one events subscription per machine at a time" }
        val job = scope.launch {
            var open: EventSubscription? = null
            var retrying = false
            try {
                val subscription = client.eventsSubscribe(null)
                open = subscription
                retries.remove(id)
                // Folder probes are not agents: the snapshot drops them as the events reducer does.
                val agentsOnly = subscription.terminals.filterNot { it.isProbe() }
                terminals.update { all -> all + (id to agentsOnly.associateBy { it.address }) }
                subscription.events.collect { event -> onEvent(id, event) }
            } catch (e: TransportLost) {
                lease.reportLost(client, e)
            } catch (e: HcException) {
                // The subscribe failed, or the channel reported an error: the session cannot serve events.
                lease.reportLost(client, TransportLost("events channel failed: ${e::class.java.simpleName}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unexpected failure (a decode error, a bug) must not leave the list empty while the machine stays connected:
                // the subscription is retried on the same client, and the last known terminals stay until it is (phase 7 r5).
                retrying = true
                Log.w(TAG, "events subscription failed: ${e::class.java.simpleName}")
                scheduleResubscribe(id, lease, client)
            } finally {
                // Cancelling this job (a new subscription, or the machine leaving Connected) must still close the connection
                // before anything else opens one: the events channel is one per machine (phase 6 P0-6).
                withContext(NonCancellable) { open?.close() }
                if (!retrying) terminals.update { it - id }
            }
        }
        subscriptions[id] = Subscription(client, job)
    }

    /**
     * Retries the events subscription on [client] after a backoff of 1 s, doubling per failure up to 30 s. It runs only while
     * the machine is still connected on that client (phase 7 r5 amendment U4).
     */
    private fun scheduleResubscribe(id: String, lease: MachineLease, client: HcClient) {
        val attempt = retries.merge(id, 1, Int::plus) ?: 1
        val delayMs = minOf(1_000L shl (attempt - 1).coerceAtMost(5), 30_000L)
        scope.launch {
            delay(delayMs)
            if (lease.client() === client) ensureSubscription(id, lease, client)
        }
    }

    /** Stops the machine's subscription and waits until it has finished, so the next one never overlaps. */
    private suspend fun endSubscription(id: String) {
        subscriptions.remove(id)?.job?.cancelAndJoin()
    }

    private fun onEvent(id: String, event: HcEvent) {
        // Any document change is worth a re-read: the whistl document is small and the read is cheap (phase 6 P0-5).
        if (event is HcEvent.DocChanged) documentChanges.notifyChanged(id)
        terminals.update { all -> all + (id to AgentEventReducer.apply(all[id].orEmpty(), event)) }
    }
}
