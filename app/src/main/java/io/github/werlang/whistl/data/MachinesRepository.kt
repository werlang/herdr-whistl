package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Rejection
import kotlinx.coroutines.flow.StateFlow

/**
 * The connection state of one machine (SPEC §1, §2). Each value is a state the UI renders; the two host-key states
 * are blocking.
 */
sealed interface ConnectionState {
    /** No session and none in progress. A retry may be scheduled. */
    data object Offline : ConnectionState

    /** A connection attempt is in progress. */
    data object Connecting : ConnectionState

    /**
     * The session is open and `hello` succeeded.
     *
     * @property hello what the core reported.
     */
    data class Connected(val hello: HelloResult) : ConnectionState

    /**
     * The last attempt failed for a reason that does not retry on its own.
     *
     * @property kind which failure.
     */
    data class Error(val kind: ErrorKind) : ConnectionState

    /**
     * The machine presented a host key nobody has confirmed. The connection waits for [MachinesRepository.confirmHostKey].
     *
     * @property algorithm the presented key type.
     * @property fingerprint the presented key's SHA-256 fingerprint.
     */
    data class HostKeyUnknown(val algorithm: String, val fingerprint: String) : ConnectionState

    /**
     * The machine presented a key other than the pinned one. This blocks: nothing connects, nothing retries, and no
     * method pins over it. Only removing the machine leaves this state.
     *
     * @property algorithm the presented key type.
     * @property expected the fingerprint of the pinned key.
     * @property actual the fingerprint of the presented key.
     */
    data class HostKeyChanged(
        val algorithm: String,
        val expected: String,
        val actual: String,
    ) : ConnectionState
}

/**
 * Why a connection is in [ConnectionState.Error].
 *
 * A failure to open is [ConnectionState.Offline] with a scheduled retry, so a machine that cannot be reached is never an error
 * (phase 6 P0-10). The kinds below are failures that retrying will not fix on its own, so none of them retries.
 */
enum class ErrorKind {
    /** The login was refused. Not retried automatically; the user can acquire again to try once more. */
    AuthFailed,

    /** `hello` reported a protocol generation other than 1. Not retried automatically. */
    UnsupportedGeneration,

    /**
     * The host answered and nothing listens on the port (`ECONNREFUSED`). Not retried automatically; the user can acquire
     * again once the SSH server runs.
     */
    ConnectionRefused,

    /**
     * The attempt failed inside the app, not on the network (a missing algorithm, a provider error, an internal error).
     * Not retried automatically: retrying repeats the same failure. Only the class name is logged.
     */
    Internal,
}

/**
 * A machine as the user typed it, before validation. Host, port and user are validated by `domain/Validation.kt`.
 *
 * @property name the display name; blank means the first label of the host.
 * @property host the host name or address.
 * @property port the SSH port.
 * @property user the SSH user name.
 */
data class NewMachine(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
) {
    /** Shows the field names only, never the host or user. */
    override fun toString(): String = "NewMachine(name=$name, <host, port, user redacted>)"
}

/** The form field a rejection refers to. */
enum class MachineField {
    /** The display name. */
    Name,

    /** The host name or address. */
    Host,

    /** The SSH port. */
    Port,

    /** The SSH user name. */
    User,

    /** The saved password. */
    Password,
}

/**
 * The outcome of [MachinesRepository.add]. Invalid input is a value, not an exception.
 */
sealed interface AddResult {
    /**
     * The machine was stored.
     *
     * @property machine the stored machine, with its new id.
     */
    data class Added(val machine: Machine) : AddResult

    /**
     * The input was rejected. Nothing was stored.
     *
     * @property field the field that failed.
     * @property reason why it failed.
     */
    data class Rejected(val field: MachineField, val reason: Rejection) : AddResult
}

/**
 * The outcome of [MachinesRepository.confirmHostKey].
 */
sealed interface ConfirmResult {
    /** The presented key is now pinned and a connection attempt has started. */
    data object Trusted : ConfirmResult

    /** There was no unknown host key waiting for this machine. */
    data object NothingPending : ConfirmResult

    /** The machine's key changed. Nothing is pinned and nothing connects; only removing the machine clears this. */
    data object Blocked : ConfirmResult
}

/**
 * A handle on a machine's live session. Holding a lease keeps the session open; releasing the last lease closes it.
 */
interface MachineLease {
    /** The machine this lease is for. */
    val machineId: String

    /**
     * The live client, while the machine is connected.
     *
     * @return the client, or `null` while the machine is not connected.
     */
    fun client(): HcClient?

    /** Gives this lease back. Releasing a second time does nothing. */
    suspend fun release()

    /**
     * Reports that the session behind [client] was lost, seen on its events channel (the 40 s watchdog). The
     * machine goes offline and retries. A report for a client that is no longer the machine's current session does
     * nothing, so a stale subscription cannot take down a newer session.
     *
     * @param client the client whose events channel failed.
     * @param cause the failure.
     */
    fun reportLost(client: HcClient, cause: TransportLost)
}

/**
 * The machines the user added, and their connections (SPEC §1, §2). One live session per machine, reference-counted
 * through [acquire]; there is no way to open a second one.
 */
interface MachinesRepository {
    /**
     * The machine list, in the order the user added them.
     *
     * @return a state flow of the list.
     */
    fun machines(): StateFlow<List<Machine>>

    /**
     * The connection state of one machine.
     *
     * @param id the machine id.
     * @return a state flow of the state.
     * @throws NoSuchElementException when no machine has that id.
     */
    fun observe(id: String): StateFlow<ConnectionState>

    /**
     * Validates and stores a new machine. A password, when given, is stored only through `SecretStore`, under the alias
     * [DefaultMachinesRepository.passwordAlias]; without one the machine logs in with the app's client key.
     *
     * @param draft the machine as typed.
     * @param password the saved password, or `null` for key login.
     * @return the stored machine, or the rejected field.
     */
    suspend fun add(draft: NewMachine, password: CharArray? = null): AddResult

    /**
     * Removes a machine: closes its session, deletes its saved password and its pinned host key.
     *
     * @param id the machine id.
     */
    suspend fun remove(id: String)

    /**
     * Takes a lease on a machine. The first lease starts a connection; later leases share the session.
     *
     * @param id the machine id.
     * @return the lease.
     * @throws NoSuchElementException when no machine has that id.
     */
    suspend fun acquire(id: String): MachineLease

    /**
     * Confirms a host key the user has read: pins the presented key and connects.
     *
     * @param id the machine id.
     * @return [ConfirmResult.Trusted] after pinning, [ConfirmResult.NothingPending] when no unknown key waits, or
     *   [ConfirmResult.Blocked] when the key changed.
     */
    suspend fun confirmHostKey(id: String): ConfirmResult

    /**
     * Restarts the connection attempt of a machine that is not connected and not waiting for a host-key decision: it stops
     * any scheduled retry and tries now. A machine that is connecting, connected, or waiting for a host-key decision is left
     * as it is.
     *
     * @param id the machine id.
     * @throws NoSuchElementException when no machine has that id.
     */
    suspend fun reconnect(id: String)
}
