package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.FramedSession
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import kotlinx.coroutines.Deferred

/**
 * One live connection to one machine's core: its transport, its command channel and a typed client after a
 * successful `hello` (SPEC §5). Exactly one of these may be open per machine; the lifecycle in `data/` enforces that.
 */
interface MachineSession {
    /** The typed client on the command channel. */
    val client: HcClient

    /** What the core reported in `hello`. */
    val hello: HelloResult

    /**
     * Completes with the reason the session was lost: the command channel closed, a read or write failed, the events
     * watchdog fired (through [reportLost]), or the app closed the session.
     */
    val lost: Deferred<TransportLost>

    /**
     * Declares the session lost, for a failure seen outside the command channel: the events watchdog (SPEC §5) is the
     * one caller. Calling it again, or after [close], does nothing.
     *
     * @param cause the failure to report.
     */
    fun reportLost(cause: TransportLost)

    /**
     * Opens a framed connection on a new channel of this session (SPEC §5.1): one more `hc connect` for one terminal's stream. The
     * command channel and the events channel are left alone. The caller closes the stream through [FramedSession.close].
     *
     * @return the framed session, after its hello.
     * @throws TransportLost when the channel cannot start, or the connection ends before the hello reply.
     * @throws io.github.werlang.whistl.core.rpc.NeedsFramed when the core does not switch the connection to framed mode.
     */
    suspend fun openStream(): FramedSession

    /** Closes the transport and every channel on it. Safe to call more than once. */
    suspend fun close()
}

/**
 * Opens [MachineSession]s. The production implementation runs SSH (see [SshMachineConnector]); tests use a fake built on
 * the replay pipes. Nothing else in the app opens a connection.
 */
interface MachineConnector {
    /**
     * Opens the transport, runs `hc connect` on the command channel, and calls `hello`.
     *
     * @param machine the machine to reach.
     * @param auth how to log in.
     * @param gate the host-key check for this connection.
     * @return the open session.
     * @throws HostKeyRefused when the presented key was not trusted; carries the decision (and, for an unknown key, the
     *   presented key).
     * @throws io.github.werlang.whistl.core.rpc.AuthFailed when the login was refused.
     * @throws io.github.werlang.whistl.core.rpc.UnsupportedGeneration when `hello` reports another generation.
     * @throws TransportLost when the connection cannot be opened.
     */
    suspend fun connect(machine: Machine, auth: SshAuth, gate: HostKeyGate): MachineSession
}
