package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.EventSubscription
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.NotFound
import io.github.werlang.whistl.core.rpc.SpawnFailed
import io.github.werlang.whistl.core.rpc.StillLive
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.Clock
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

    /**
     * Acts on an agent from the list, without opening its screen (phase 9, SPEC §2.4).
     *
     * @param machineId the machine the agent runs on.
     * @param address the agent's terminal address.
     * @param action what the user asked for.
     * @return what happened.
     */
    suspend fun act(machineId: String, address: String, action: AgentAction): ActionResult
}

/** What the user can ask of an agent from the list (phase 9, SPEC §2.4). */
sealed interface AgentAction {
    /** Sends the interrupt the core defines: the agent stops what it is doing and waits. */
    data object Interrupt : AgentAction

    /** Stops the agent and removes the terminal, so it leaves the list. */
    data object Close : AgentAction

    /** @property name the name to show from now on, as the `whistl.name` label. */
    data class Rename(val name: String) : AgentAction
}

/** What came of an action taken from the list. */
sealed interface ActionResult {
    /** The core accepted it. */
    data object Done : ActionResult

    /** The machine is not connected, so nothing was sent. */
    data object Offline : ActionResult

    /** The agent is gone already: the action had nothing to act on. */
    data object Gone : ActionResult

    /** @property action what failed, for the message the list shows. */
    data class Failed(val action: AgentAction) : ActionResult
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
    // Monotonic and plain JVM: the recency only compares stamps with each other, and a unit test must not need Android.
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
) : AgentsRepository {
    private val terminals = MutableStateFlow<Map<String, Map<String, Terminal>>>(emptyMap())
    private val watchers = ConcurrentHashMap<String, Job>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    /** When each agent last changed here, keyed `machine/address`: the recency the list sorts by (0.5.1). */
    private val updated = ConcurrentHashMap<String, Long>()

    /** Failed subscription attempts in a row per machine: the backoff grows with it, and a healthy subscription clears it. */
    private val retries = ConcurrentHashMap<String, Int>()

    /** The retry scheduled for each machine after a failed subscription, so leaving Connected can cancel it (p7r8 item 3). */
    private val resubscribes = ConcurrentHashMap<String, Job>()

    private companion object {
        const val TAG: String = "AgentsRepository"

        /**
         * How long a subscription must stay up before the retry count resets. A poison event that fails right after every snapshot
         * then backs off instead of retrying each second (p7r8 item 4).
         */
        const val HEALTHY_AFTER_MS: Long = 30_000L

        /** How many times a remove is retried while the core still counts the killed terminal as live. */
        const val REMOVE_TRIES: Int = 5

        /** How long to wait between those tries. */
        const val REMOVE_DELAY_MS: Long = 150L
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
                MachineAgent(machine.id, Agent(terminal), space, updated["${machine.id}/${terminal.address}"] ?: 0L)
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

    override suspend fun act(machineId: String, address: String, action: AgentAction): ActionResult {
        val lease = try {
            machines.acquire(machineId)
        } catch (e: NoSuchElementException) {
            return ActionResult.Offline
        }
        try {
            val client = lease.client() ?: return ActionResult.Offline
            when (action) {
                AgentAction.Interrupt -> client.terminalInterrupt(address)
                // Closing is stop then remove: killing a terminal that already finished is not an error, so the kill's
                // NotFound is swallowed and the remove is what decides.
                AgentAction.Close -> {
                    try {
                        client.terminalKill(address)
                    } catch (e: NotFound) {
                        // Already finished: the remove below is the part that matters.
                    }
                    removeWhenExited(client, address)
                }
                is AgentAction.Rename -> {
                    val name = action.name.trim()
                    if (name.isEmpty()) return ActionResult.Failed(action)
                    client.terminalLabel(address, mapOf(Agent.NAME_LABEL to name))
                }
            }
            return ActionResult.Done
        } catch (e: NotFound) {
            return ActionResult.Gone
        } catch (e: TransportLost) {
            return ActionResult.Offline
        } catch (e: HcException) {
            return ActionResult.Failed(action)
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    /**
     * Removes a terminal, waiting for the core to consider it exited.
     *
     * `terminal.kill` returns before the process is reaped, and `terminal.rm` answers `StillLive` until it is, so a
     * remove right after a kill is a race the user would see as "the core refused the action" (0.4.0 review, P2). The
     * wait is short and bounded: five tries, 150 ms apart.
     *
     * @param client the live connection.
     * @param address the terminal to remove.
     */
    private suspend fun removeWhenExited(client: HcClient, address: String) {
        var attempt = 0
        while (true) {
            try {
                client.terminalRm(address)
                return
            } catch (e: StillLive) {
                attempt++
                if (attempt >= REMOVE_TRIES) throw e
                delay(REMOVE_DELAY_MS)
            }
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
                    // Not connected: no retry may run, and the agents leave the list. A subscription that failed while the machine
                    // was connected kept them, so they are removed here too (p7r8 item 3).
                    cancelResubscribe(id)
                    endSubscription(id)
                    terminals.update { it - id }
                }
            }
        } catch (e: NoSuchElementException) {
            return
        } finally {
            withContext(NonCancellable) {
                cancelResubscribe(id)
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
                // Folder probes are not agents: the snapshot drops them as the events reducer does.
                val agentsOnly = subscription.terminals.filterNot { it.isProbe() }
                terminals.update { all ->
                    val previous = all[id].orEmpty()
                    val next = agentsOnly.associateBy { it.address }
                    stampChanged(id, previous, next)
                    all + (id to next)
                }
                // The retry count resets only once the subscription has stayed up for a while (p7r8 item 4).
                val healthy = launch {
                    delay(HEALTHY_AFTER_MS)
                    retries.remove(id)
                }
                try {
                    subscription.events.collect { event -> onEvent(id, event) }
                } finally {
                    healthy.cancel()
                }
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
        resubscribes[id] = scope.launch {
            delay(delayMs)
            if (lease.client() === client) ensureSubscription(id, lease, client)
        }
    }

    /** Cancels the retry scheduled for the machine, if one is pending. */
    private fun cancelResubscribe(id: String) {
        resubscribes.remove(id)?.cancel()
    }

    /** Stops the machine's subscription and waits until it has finished, so the next one never overlaps. */
    private suspend fun endSubscription(id: String) {
        subscriptions.remove(id)?.job?.cancelAndJoin()
    }

    private fun onEvent(id: String, event: HcEvent) {
        // Any document change is worth a re-read: the whistl document is small and the read is cheap (phase 6 P0-5).
        if (event is HcEvent.DocChanged) documentChanges.notifyChanged(id)
        terminals.update { all ->
            val previous = all[id].orEmpty()
            val next = AgentEventReducer.apply(previous, event)
            stampChanged(id, previous, next)
            all + (id to next)
        }
    }

    /**
     * Records the moment an agent changed, for the list's order: only the agents whose terminal is not the same object
     * as before are stamped, so an unrelated event does not make every row look fresh.
     *
     * @param machineId the machine the terminals belong to.
     * @param previous the terminals before the change.
     * @param next the terminals after it.
     */
    private fun stampChanged(machineId: String, previous: Map<String, Terminal>, next: Map<String, Terminal>) {
        val now = clock.nowMillis()
        next.forEach { (address, terminal) ->
            if (previous[address] != terminal) updated["$machineId/$address"] = now
        }
    }
}
