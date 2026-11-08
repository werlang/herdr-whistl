package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.CoreMissing
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.JsonRpcConnection
import io.github.werlang.whistl.core.rpc.LinePipe
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The production [MachineConnector]: an [SshjTransport] per connection, the command channel as a [JsonRpcConnection],
 * and `hello` before a session is handed out (SPEC §5, §6).
 *
 * Not covered by JVM tests: they open no socket. The lifecycle around it is covered with a fake connector.
 *
 * @param scope the scope for the command connection's reader; the lifecycle passes its own scope.
 * @param io the dispatcher for blocking SSH calls.
 */
class SshMachineConnector(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MachineConnector {
    override suspend fun connect(machine: Machine, auth: SshAuth, gate: HostKeyGate): MachineSession {
        val transport = SshjTransport(
            SshTarget(machine.id, machine.host, machine.port, machine.user),
            auth,
            gate,
            io,
        )
        transport.open()
        return try {
            openSession(transport)
        } catch (e: Throwable) {
            withContext(NonCancellable) { transport.close() }
            throw e
        }
    }

    private suspend fun openSession(transport: SshjTransport): MachineSession {
        val lost = CompletableDeferred<TransportLost>()
        val command = ObservedPipe(transport.exec(SshTransport.HC_CONNECT_COMMAND), lost)
        val client = HcClient(
            command = JsonRpcConnection(command, scope),
            openEventsPipe = { transport.exec(SshTransport.HC_CONNECT_COMMAND) },
            scope = scope,
        )
        val hello = try {
            client.hello()
        } catch (e: TransportLost) {
            // The login worked, and the command ended before it answered. Exit status 127 is "command not found": herdr-core is not
            // installed for this login. That is not a network failure, so it does not read as "could not reach the machine".
            if (isCoreMissing(transport.hcExitStatus())) throw CoreMissing(e)
            throw e
        }
        return SshMachineSession(transport, client, hello, lost)
    }
}

/**
 * The production [MachineSession]: owns the transport, and completes [lost] when the command channel fails or the
 * session is closed.
 */
private class SshMachineSession(
    private val transport: SshTransport,
    override val client: HcClient,
    override val hello: HelloResult,
    private val signal: CompletableDeferred<TransportLost>,
) : MachineSession {
    override val lost: Deferred<TransportLost> = signal

    override fun reportLost(cause: TransportLost) {
        signal.complete(cause)
    }

    override suspend fun close() {
        signal.complete(TransportLost("session closed by the app"))
        transport.close()
    }
}

/**
 * A command-channel pipe that reports loss: a read or write that fails, or a channel that ends, completes [lost].
 * Every call on the client goes through this pipe, so a failed call and a dead channel are seen the same way.
 *
 * @param inner the real pipe.
 * @param lost completed with the first loss observed.
 */
private class ObservedPipe(
    private val inner: LinePipe,
    private val lost: CompletableDeferred<TransportLost>,
) : LinePipe {
    override suspend fun writeLine(line: String) {
        try {
            inner.writeLine(line)
        } catch (e: TransportLost) {
            lost.complete(e)
            throw e
        }
    }

    override suspend fun readLine(): String? {
        val line = try {
            inner.readLine()
        } catch (e: TransportLost) {
            lost.complete(e)
            throw e
        }
        if (line == null) lost.complete(TransportLost("hc closed the command channel"))
        return line
    }

    override suspend fun close() {
        inner.close()
    }
}
