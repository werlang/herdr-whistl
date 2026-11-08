package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.core.rpc.UnsupportedGeneration
import io.github.werlang.whistl.core.secure.SecretStore
import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.core.ssh.FailureStage
import io.github.werlang.whistl.core.ssh.HostKeyDecision
import io.github.werlang.whistl.core.ssh.HostKeyGate
import io.github.werlang.whistl.core.ssh.HostKeyPolicy
import io.github.werlang.whistl.core.ssh.HostKeyRefused
import io.github.werlang.whistl.core.ssh.HostKeyStore
import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.core.ssh.MachineConnector
import io.github.werlang.whistl.core.ssh.androidErrno
import io.github.werlang.whistl.core.ssh.classChain
import io.github.werlang.whistl.core.ssh.failureStage
import io.github.werlang.whistl.core.ssh.MachineSession
import io.github.werlang.whistl.core.ssh.PresentedHostKey
import io.github.werlang.whistl.core.ssh.SshAuth
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.domain.Validation
import io.github.werlang.whistl.domain.validateHost
import io.github.werlang.whistl.domain.validateMachineName
import io.github.werlang.whistl.domain.validatePort
import io.github.werlang.whistl.domain.validateUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The default [MachinesRepository]: a connection state machine per machine, with one session shared by reference
 * count and a retry schedule from [Backoff] (round p3r1, SPEC §2, §6).
 *
 * Transitions:
 * - first [acquire]: `Offline` → `Connecting` → `Connected`, or one of the other states below.
 * - [ConnectionState.Error] with `AuthFailed`, `UnsupportedGeneration`, `ConnectionRefused` or `Internal`: no automatic retry.
 *   A later [acquire] tries once. Only a network failure (see [failureStage]) goes to `Offline` with a retry.
 * - `HostKeyUnknown`: waits for [confirmHostKey], which pins the presented key and connects.
 * - `HostKeyChanged`: blocks. Nothing connects, nothing retries, and no method pins over it. Only [remove] leaves it.
 * - a lost session (`TransportLost` on open, on a call, or reported by the events watchdog): `Offline`, the dead session
 *   is closed, and a retry is scheduled while any lease is held.
 *
 * Invariant: at most one session per machine is open. A lost session is closed before its retry starts, and a session
 * is closed when its last lease is released.
 *
 * Time: retries use [delay] on [scope], so tests drive them with virtual time. No wall clock is read.
 *
 * @param scope the scope for connection attempts and watchers; tests pass `backgroundScope`.
 * @param store the stored machine list.
 * @param secrets where saved passwords are kept.
 * @param keys the app's client key, used for key login.
 * @param hostKeys the pinned host keys.
 * @param connector opens sessions.
 * @param newId produces machine ids; defaults to a random UUID.
 */
class DefaultMachinesRepository(
    private val scope: CoroutineScope,
    private val store: MachineStore,
    private val secrets: SecretStore,
    private val keys: KeyManager,
    private val hostKeys: HostKeyStore,
    private val connector: MachineConnector,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val errnoOf: (Throwable) -> Int? = ::androidErrno,
    private val reportInternal: (String) -> Unit = { Log.w("Machines", it) },
) : MachinesRepository {
    private val policy = HostKeyPolicy(hostKeys)
    private val lock = Mutex()
    private val slots = ConcurrentHashMap<String, Slot>()
    private val machineList = MutableStateFlow<List<Machine>>(emptyList())

    init {
        store.list().forEach { slots[it.machine.id] = Slot(it) }
        publishList()
    }

    override fun machines(): StateFlow<List<Machine>> = machineList.asStateFlow()

    override fun observe(id: String): StateFlow<ConnectionState> = slotFor(id).state.asStateFlow()

    override suspend fun add(draft: NewMachine, password: CharArray?): AddResult {
        val host = when (val r = validateHost(draft.host)) {
            is Validation.Valid -> r.value
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
        val name = when (val r = validateMachineName(draft.name.ifBlank { defaultName(host.text) })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return AddResult.Rejected(MachineField.Name, r.reason)
        }
        if (password != null && password.isEmpty()) return AddResult.Rejected(MachineField.Password, Rejection.EMPTY)

        val id = newId()
        val machine = Machine(id = id, name = name, host = host.text, port = port, user = user)
        val stored = StoredMachine(machine, if (password == null) AuthMethod.Key else AuthMethod.Password)
        lock.withLock {
            if (password != null) {
                secrets.put(passwordAlias(id), String(password).toByteArray(Charsets.UTF_8))
            }
            store.put(stored)
            slots[id] = Slot(stored)
            publishList()
        }
        return AddResult.Added(machine)
    }

    override suspend fun remove(id: String) {
        lock.withLock {
            val slot = slots.remove(id) ?: return
            slot.job?.cancel()
            slot.job = null
            slot.holders = 0
            val dying = slot.session
            slot.session = null
            dying?.close()
            store.delete(id)
            secrets.delete(passwordAlias(id))
            hostKeys.forget(id)
            publishList()
        }
    }

    override suspend fun acquire(id: String): MachineLease {
        lock.withLock {
            val slot = slotFor(id)
            slot.holders++
            if (slot.session == null && slot.job == null && canConnect(slot.state.value)) {
                startAttempt(slot, delayMillis = 0L)
            }
        }
        return Lease(id)
    }

    override suspend fun reconnect(id: String) {
        lock.withLock {
            val slot = slotFor(id)
            if (slot.holders == 0 || !canConnect(slot.state.value)) return
            slot.job?.cancel()
            slot.job = null
            slot.attempt = 0
            startAttempt(slot, delayMillis = 0L)
        }
    }

    override suspend fun confirmHostKey(id: String): ConfirmResult = lock.withLock {
        val slot = slotFor(id)
        when (slot.state.value) {
            is ConnectionState.HostKeyChanged -> ConfirmResult.Blocked
            is ConnectionState.HostKeyUnknown -> {
                val presented = slot.pendingKey ?: return@withLock ConfirmResult.NothingPending
                when (val decision = policy.trust(id, presented.algorithm, presented.blob)) {
                    HostKeyDecision.Trusted -> {
                        slot.pendingKey = null
                        slot.attempt = 0
                        if (slot.holders > 0) {
                            startAttempt(slot, delayMillis = 0L)
                        } else {
                            slot.state.value = ConnectionState.Offline
                        }
                        ConfirmResult.Trusted
                    }

                    is HostKeyDecision.Changed -> {
                        slot.pendingKey = null
                        slot.state.value = ConnectionState.HostKeyChanged(
                            decision.algorithm,
                            decision.expectedFingerprint,
                            decision.actualFingerprint,
                        )
                        ConfirmResult.Blocked
                    }

                    is HostKeyDecision.Unknown -> ConfirmResult.NothingPending
                }
            }

            else -> ConfirmResult.NothingPending
        }
    }

    /** A lease on one machine. Releasing the last lease closes the session. */
    private inner class Lease(override val machineId: String) : MachineLease {
        private val released = AtomicBoolean(false)

        override fun client(): HcClient? {
            val slot = slots[machineId] ?: return null
            return if (slot.state.value is ConnectionState.Connected) slot.session?.client else null
        }

        override suspend fun release() {
            if (released.compareAndSet(false, true)) releaseSlot(machineId)
        }

        override fun reportLost(client: HcClient, cause: TransportLost) {
            val session = slots[machineId]?.session ?: return
            if (session.client === client) session.reportLost(cause)
        }
    }

    /** Drops one lease; with none left, stops the attempt, closes the session and goes offline. */
    private suspend fun releaseSlot(id: String) {
        lock.withLock {
            val slot = slots[id] ?: return
            if (slot.holders > 0) slot.holders--
            if (slot.holders > 0) return
            slot.job?.cancel()
            slot.job = null
            slot.attempt = 0
            val dying = slot.session
            slot.session = null
            dying?.close()
            when (slot.state.value) {
                ConnectionState.Connecting, is ConnectionState.Connected -> slot.state.value = ConnectionState.Offline
                else -> Unit
            }
        }
    }

    /** One connection attempt, from start to a stable state. Runs in a job owned by the slot. */
    private suspend fun attempt(slot: Slot) {
        lock.withLock { slot.state.value = ConnectionState.Connecting }
        val gate = HostKeyGate(policy, slot.machine.id)
        val session = try {
            connector.connect(slot.machine, resolveAuth(slot.stored), gate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyRefused) {
            onHostKeyRefused(slot, e)
            return
        } catch (e: AuthFailed) {
            finish(slot, ConnectionState.Error(ErrorKind.AuthFailed))
            return
        } catch (e: UnsupportedGeneration) {
            finish(slot, ConnectionState.Error(ErrorKind.UnsupportedGeneration))
            return
        } catch (e: Throwable) {
            // Only a network failure retries on its own. A refused port or a failure inside the app is an error the user
            // acts on (phase 7 r1c): retrying would repeat it, and the app's own failure must not read as "unreachable".
            when (failureStage(e, errnoOf)) {
                FailureStage.Unreachable -> onOffline(slot)
                FailureStage.Refused -> finish(slot, ConnectionState.Error(ErrorKind.ConnectionRefused))
                FailureStage.Internal -> {
                    reportInternal("connection failed inside the app: ${classChain(e)}")
                    finish(slot, ConnectionState.Error(ErrorKind.Internal))
                }
            }
            return
        }
        lock.withLock {
            slot.job = null
            if (slot.holders == 0) {
                // Released while connecting: nothing may stay open.
                session.close()
                slot.state.value = ConnectionState.Offline
                return
            }
            slot.attempt = 0
            slot.session = session
            slot.state.value = ConnectionState.Connected(session.hello)
            watch(slot, session)
        }
    }

    private suspend fun onHostKeyRefused(slot: Slot, refused: HostKeyRefused) {
        lock.withLock {
            slot.job = null
            when (val decision = refused.decision) {
                is HostKeyDecision.Unknown -> {
                    slot.pendingKey = refused.presented
                    slot.state.value = ConnectionState.HostKeyUnknown(decision.algorithm, decision.fingerprint)
                }

                is HostKeyDecision.Changed -> {
                    slot.pendingKey = null
                    slot.state.value = ConnectionState.HostKeyChanged(
                        decision.algorithm,
                        decision.expectedFingerprint,
                        decision.actualFingerprint,
                    )
                }

                HostKeyDecision.Trusted -> {
                    // A refusal never carries a trusted decision; treat it as a failed open.
                    slot.state.value = ConnectionState.Offline
                    scheduleRetry(slot)
                }
            }
        }
    }

    private suspend fun finish(slot: Slot, state: ConnectionState) {
        lock.withLock {
            slot.job = null
            slot.state.value = state
        }
    }

    private suspend fun onOffline(slot: Slot) {
        lock.withLock {
            slot.job = null
            slot.state.value = ConnectionState.Offline
            scheduleRetry(slot)
        }
    }

    /** A session was lost. The dead session is closed first, then the state goes offline and a retry is scheduled. */
    private suspend fun onLost(slot: Slot, session: MachineSession) {
        lock.withLock {
            if (slot.session !== session) return
            slot.session = null
            session.close()
            slot.job = null
            slot.state.value = ConnectionState.Offline
            scheduleRetry(slot)
        }
    }

    /** Watches one live session and hands its loss to [onLost]. */
    private fun watch(slot: Slot, session: MachineSession) {
        scope.launch {
            session.lost.await()
            onLost(slot, session)
        }
    }

    /** Starts the slot's attempt after [delayMillis], unless a lease is no longer held. Call with the lock held. */
    private fun startAttempt(slot: Slot, delayMillis: Long) {
        slot.job = scope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            attempt(slot)
        }
    }

    /** Schedules the next retry with backoff while any lease is held. Call with the lock held. */
    private fun scheduleRetry(slot: Slot) {
        if (slot.holders == 0) return
        val delayMillis = Backoff.delayMillis(slot.attempt)
        slot.attempt++
        startAttempt(slot, delayMillis)
    }

    private fun slotFor(id: String): Slot = slots[id] ?: throw NoSuchElementException("no machine with id $id")

    private fun publishList() {
        machineList.value = store.list().map { it.machine }
    }

    private fun resolveAuth(stored: StoredMachine): SshAuth = when (stored.auth) {
        AuthMethod.Key -> {
            keys.ensureKey()
            SshAuth.Key(keys.loadSeed())
        }

        AuthMethod.Password -> {
            val bytes = secrets.get(passwordAlias(stored.machine.id)) ?: throw AuthFailed()
            SshAuth.Password(String(bytes, Charsets.UTF_8).toCharArray())
        }
    }

    /** A connection can start from these states: nothing is open, and no blocking host-key state applies. */
    private fun canConnect(state: ConnectionState): Boolean = when (state) {
        ConnectionState.Offline, is ConnectionState.Error -> true
        ConnectionState.Connecting, is ConnectionState.Connected,
        is ConnectionState.HostKeyUnknown, is ConnectionState.HostKeyChanged -> false
    }

    /** Per-machine bookkeeping. Every field is read and written under [lock] except the observable state. */
    private class Slot(val stored: StoredMachine) {
        val machine: Machine get() = stored.machine
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Offline)

        @Volatile
        var session: MachineSession? = null
        var holders: Int = 0
        var job: Job? = null
        var attempt: Int = 0
        var pendingKey: PresentedHostKey? = null
    }

    companion object {
        /**
         * The [SecretStore] alias a machine's saved password is stored under: `machine.password.<machine id>`.
         *
         * @param machineId the machine id.
         * @return the alias.
         */
        fun passwordAlias(machineId: String): String = "machine.password.$machineId"

        /** A blank display name defaults to the first label of the host (SPEC §1). */
        private fun defaultName(host: String): String = host.substringBefore('.')
    }
}
