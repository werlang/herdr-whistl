package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** The agents on connected machines and the Agents badge count (SPEC §1, §2). */
interface AgentsRepository {
    /** Every agent on a connected machine, each with its machine and its matched space, in machine order. */
    val agents: StateFlow<List<MachineAgent>>

    /** How many agents are blocked: the Agents badge (SPEC §2). Zero hides the badge. */
    val blockedCount: StateFlow<Int>
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

    /** One live events subscription: the client it was opened on, and the job that reads it. */
    private class Subscription(val client: HcClient, val job: Job)

    override val agents: StateFlow<List<MachineAgent>> = combine(terminals, spaces.groups(), machines.machines()) {
        byMachine, groups, list ->
        list.flatMap { machine ->
            val spacesOfMachine = groups.firstOrNull { it.machineId == machine.id }?.spaces.orEmpty()
            byMachine[machine.id].orEmpty().values.map { terminal ->
                MachineAgent(machine.id, Agent(terminal), matchSpace(terminal, spacesOfMachine))
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val blockedCount: StateFlow<Int> = agents
        .map { list -> list.count { it.agent.terminal.status == AgentStatus.Blocked } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch {
            machines.machines().collect { list -> reconcile(list.map { it.id }.toSet()) }
        }
    }

    /** Starts a watcher for each new machine and stops the watcher of each removed one. */
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
            try {
                val subscription = client.eventsSubscribe(null)
                terminals.update { all -> all + (id to subscription.terminals.associateBy { it.address }) }
                subscription.events.collect { event -> onEvent(id, event) }
            } catch (e: TransportLost) {
                lease.reportLost(client, e)
            } catch (e: HcException) {
                // The subscribe failed, or the channel reported an error: the session cannot serve events.
                lease.reportLost(client, TransportLost("events channel failed: ${e::class.java.simpleName}"))
            } finally {
                terminals.update { it - id }
            }
        }
        subscriptions[id] = Subscription(client, job)
    }

    /** Stops the machine's subscription and waits until it has finished, so the next one never overlaps. */
    private suspend fun endSubscription(id: String) {
        subscriptions.remove(id)?.job?.cancelAndJoin()
    }

    private fun onEvent(id: String, event: HcEvent) {
        if (event is HcEvent.DocChanged && (event.data["name"] as? JsonPrimitive)?.content == WHISTL_DOCUMENT) {
            documentChanges.notifyChanged(id)
        }
        terminals.update { all -> all + (id to AgentEventReducer.apply(all[id].orEmpty(), event)) }
    }
}
