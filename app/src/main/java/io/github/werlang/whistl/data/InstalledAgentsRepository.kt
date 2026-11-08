package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.KnownAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The known coding agents each machine has installed, as state (phase 4b r1, §2.1). The Machines cards and the New-agent
 * picker read the same flow.
 */
interface InstalledAgentsRepository {
    /**
     * The installed agents of each machine, by machine id. A machine appears once it has been detected while connected; it
     * keeps that last list while offline, and leaves the map when the machine is removed.
     */
    val installed: StateFlow<Map<String, List<KnownAgent>>>
}

/**
 * The default [InstalledAgentsRepository]. For each machine it holds a lease, and each time the machine becomes `Connected` it
 * runs [InstalledAgentsDetector.installed] on the live client. The detector's own 60 s throttle decides whether a machine
 * is asked again, so a quick reconnect costs no command.
 *
 * A failed detection keeps the previous list. A lost connection is not a result: nothing is published for it.
 *
 * @param scope the scope for the watchers.
 * @param machines the machines and their leases.
 * @param detector runs the detection and holds its throttle.
 */
class DefaultInstalledAgentsRepository(
    private val scope: CoroutineScope,
    private val machines: MachinesRepository,
    private val detector: InstalledAgentsDetector,
) : InstalledAgentsRepository {
    private val map = MutableStateFlow<Map<String, List<KnownAgent>>>(emptyMap())
    private val watchers = ConcurrentHashMap<String, Job>()

    override val installed: StateFlow<Map<String, List<KnownAgent>>> = map.asStateFlow()

    init {
        scope.launch {
            machines.machines().collect { list -> reconcile(list.map { it.id }.toSet()) }
        }
    }

    /** Starts a watcher for each new machine, and stops the watcher and forgets the list of each removed one. */
    private suspend fun reconcile(current: Set<String>) {
        for (id in watchers.keys.toList()) {
            if (id !in current) {
                watchers.remove(id)?.cancelAndJoin()
                map.update { it - id }
            }
        }
        for (id in current) {
            if (!watchers.containsKey(id)) watchers[id] = scope.launch { watch(id) }
        }
    }

    /** Holds the machine's lease and detects its agents each time it connects. */
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
                    if (client != null) detect(id, client)
                }
            }
        } catch (e: NoSuchElementException) {
            return
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    /** Runs the detection on the live client. A failure keeps the list already held. */
    private suspend fun detect(id: String, client: io.github.werlang.whistl.core.rpc.HcClient) {
        val agents = try {
            detector.installed(id, client)
        } catch (e: TransportLost) {
            return
        } catch (e: HcException) {
            return
        }
        map.update { it + (id to agents) }
    }
}
