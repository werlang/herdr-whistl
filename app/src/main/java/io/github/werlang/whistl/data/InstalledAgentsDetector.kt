package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.domain.Clock
import io.github.werlang.whistl.domain.KnownAgent
import io.github.werlang.whistl.domain.KnownAgents
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Which known coding agents a machine has installed (SPEC §5), detected by one constant script and throttled per
 * machine.
 *
 * The detection call is `machine.exec` with [KnownAgents.detectionArgv], so the request is the one recorded in
 * `exec_installed.json`. Results are cached per machine. A machine is re-run at most once per [minIntervalMillis]
 * (60 s), measured on the injected [Clock]. A call inside that window returns the cached list without sending anything.
 *
 * A failed call is not cached, so the next call tries again. Machines are independent: each has its own lock, so a slow
 * machine does not hold up another.
 *
 * @param clock the time source; tests back it with the virtual scheduler.
 * @param minIntervalMillis the least time between two runs for one machine; defaults to 60 000 ms.
 */
class InstalledAgentsDetector(
    private val clock: Clock,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The installed known agents on a machine, from the cache when it is fresh, otherwise from a new detection run.
     *
     * @param machineId the machine id, the cache key.
     * @param client the command client of a live session on that machine.
     * @return the agents found, in the order the script printed them.
     */
    suspend fun installed(machineId: String, client: HcClient): List<KnownAgent> {
        val mutex = locks.getOrPut(machineId) { Mutex() }
        return mutex.withLock {
            val now = clock.nowMillis()
            val cached = cache[machineId]
            if (cached != null && now - cached.at < minIntervalMillis) {
                cached.agents
            } else {
                val exec = client.machineExec(KnownAgents.detectionArgv)
                val agents = KnownAgents.parseInstalled(exec.stdout)
                cache[machineId] = Cached(now, agents)
                agents
            }
        }
    }

    /** One cached detection result and the time it was taken. */
    private class Cached(val at: Long, val agents: List<KnownAgent>)

    companion object {
        /** The least time between two detection runs for one machine: 60 seconds (SPEC §5). */
        const val MIN_INTERVAL_MILLIS: Long = 60_000L
    }
}
