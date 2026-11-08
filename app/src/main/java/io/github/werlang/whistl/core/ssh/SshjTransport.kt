package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.LinePipe
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.PrivateKey
import java.security.PublicKey

/**
 * How long an SSH session may take (phase 6 P0-3). [connectMillis] bounds the TCP connect and the handshake, so a machine stuck
 * in `Connecting` reaches an error or offline state instead of waiting for the OS. [socketMillis] bounds each socket read and
 * write, so a stuck write fails rather than holding its caller.
 *
 * @property connectMillis the connect timeout, in milliseconds. Default 15 s.
 * @property socketMillis the socket timeout, in milliseconds. Default 30 s.
 */
data class SshTimeouts(
    val connectMillis: Int = 15_000,
    val socketMillis: Int = 30_000,
)

/**
 * The part of sshj's client that [SshjTransport] uses (phase 6 P0-3). The default is sshj ([SshjSession]); the tests use a fake
 * that records each call, so the timeouts, the close-on-failure path and the stderr drain are checked without a network.
 */
internal interface SshSessionPort {
    /**
     * Sets the connect timeout and the socket timeout. Called before [connect].
     *
     * @param connectMillis the connect timeout.
     * @param socketMillis the socket timeout.
     */
    fun setTimeouts(connectMillis: Int, socketMillis: Int)

    /** Adds the host-key check that every [connect] runs. */
    fun addHostKeyVerifier(verifier: HostKeyVerifier)

    /**
     * Connects the TCP socket and runs the SSH handshake.
     *
     * @param host the host.
     * @param port the port.
     */
    fun connect(host: String, port: Int)

    /**
     * Logs in with the app's Ed25519 key.
     *
     * @param user the user name.
     * @param seed the 32-byte seed.
     */
    fun authPublickey(user: String, seed: ByteArray)

    /**
     * Logs in with a password.
     *
     * @param user the user name.
     * @param password the password characters.
     */
    fun authPassword(user: String, password: CharArray)

    /**
     * Starts [command] on a new channel of this session.
     *
     * @param command the command line.
     * @return the channel.
     */
    fun exec(command: String): ExecChannelPort

    /** Disconnects the session and every channel on it. Safe to call more than once. */
    fun disconnect()
}

/** One exec channel: the streams hc speaks on, and its stderr, which must be drained. */
internal interface ExecChannelPort {
    /** Where the client writes requests. */
    val stdin: OutputStream

    /** Where hc writes replies, one JSON message per line. */
    val stdout: InputStream

    /** Where hc writes its diagnostics. Never shown or logged; it is read so the channel cannot fill up (phase 6 P0-3). */
    val stderr: InputStream

    /** Closes the channel and its session. */
    fun close()
}

/**
 * [SshTransport] over sshj (SPEC §5, §6). One SSH session per machine: [open] connects, verifies the host key through
 * [HostKeyGate], and authenticates. Each [exec] opens a new channel on that session and runs [HC_CONNECT_COMMAND].
 *
 * Failure and cancellation (phase 6 P0-3): [open] closes the session whatever ended it, under [NonCancellable]. A cancellation
 * while connect or login is blocked disconnects the session, so the blocked call returns.
 *
 * Blocking reads run on [io]. A read cannot be cancelled while blocked; the events watchdog therefore fails the connection, and
 * the caller then closes the pipe, which ends the read.
 *
 * @param target the machine to reach.
 * @param auth how to log in.
 * @param gate the host-key check for this machine.
 * @param io the dispatcher for blocking SSH calls.
 * @param timeouts the connect and socket timeouts.
 * @param newSession makes the session. The default is sshj; tests pass a fake.
 */
class SshjTransport internal constructor(
    override val target: SshTarget,
    private val auth: SshAuth,
    private val gate: HostKeyGate,
    private val io: CoroutineDispatcher,
    private val timeouts: SshTimeouts,
    private val newSession: () -> SshSessionPort,
) : SshTransport {
    /** Uses the default timeouts and sshj. */
    constructor(
        target: SshTarget,
        auth: SshAuth,
        gate: HostKeyGate,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(target, auth, gate, io, SshTimeouts(), { SshjSession(SSHClient()) })

    @Volatile
    private var session: SshSessionPort? = null

    /**
     * Connects, verifies the host key and authenticates. A second call does nothing. Any failure, or a cancellation, closes the
     * session before the error goes on.
     *
     * @throws HostKeyRefused when the presented host key was not trusted (see [gate]).
     * @throws TransportLost when the connection or the login fails for another reason.
     */
    override suspend fun open() {
        if (session != null) return
        val port = newSession()
        try {
            coroutineScope {
                val connecting = async(io) { connectAndLogin(port) }
                try {
                    connecting.await()
                } catch (e: CancellationException) {
                    // Cancelled while connect or login may be blocked: closing the session makes the blocked call return.
                    withContext(NonCancellable + io) { runCatching { port.disconnect() } }
                    throw e
                }
            }
            session = port
        } catch (e: Throwable) {
            if (session !== port) withContext(NonCancellable + io) { runCatching { port.disconnect() } }
            throw e
        }
    }

    /**
     * The blocking part of [open]: applies the timeouts, connects, and logs in. Runs on [io].
     *
     * @param port the session to connect.
     * @throws HostKeyRefused when the host key was not trusted.
     * @throws TransportLost when the connection or the login fails for another reason.
     */
    private fun connectAndLogin(port: SshSessionPort) {
        port.setTimeouts(timeouts.connectMillis, timeouts.socketMillis)
        port.addHostKeyVerifier(SshjHostKeyVerifier(gate))
        try {
            port.connect(target.host, target.port)
            when (val credentials = auth) {
                is SshAuth.Key -> port.authPublickey(target.user, credentials.seedBytes())
                is SshAuth.Password -> port.authPassword(target.user, credentials.passwordChars())
            }
        } catch (e: IOException) {
            runCatching { port.disconnect() }
            gate.lastDecision?.takeUnless { it is HostKeyDecision.Trusted }?.let { decision ->
                throw HostKeyRefused(decision, gate.lastPresented.takeIf { decision is HostKeyDecision.Unknown })
            }
            throw openFailure(e)
        }
    }

    /**
     * Opens a channel running [command]. Only [HC_CONNECT_COMMAND] is accepted.
     *
     * @param command must equal [SshTransport.HC_CONNECT_COMMAND].
     * @return the pipe for the new channel.
     * @throws IllegalArgumentException for any other command.
     * @throws TransportLost when the session is not open or the channel cannot start.
     */
    override suspend fun exec(command: String): LinePipe {
        require(command == SshTransport.HC_CONNECT_COMMAND) { "only the hc connect command may run" }
        return withContext(io) {
            val port = session ?: throw TransportLost("ssh session is not open")
            val channel = try {
                port.exec(command)
            } catch (e: IOException) {
                throw TransportLost("could not start hc connect")
            }
            SshjLinePipe(channel, io)
        }
    }

    /** Disconnects the SSH session and so every channel on it. */
    override suspend fun close() {
        withContext(io) {
            session?.let { runCatching { it.disconnect() } }
            session = null
        }
    }

    override fun toString(): String = "SshjTransport(${target.machineId}, ${auth})"
}

/**
 * Maps a failure while connecting or logging in to the typed error the lifecycle needs.
 *
 * A login the server refused (sshj's [UserAuthException]) is [AuthFailed], which is not retried. Anything else is
 * [TransportLost]. Messages name neither the host, the user nor any credential.
 *
 * @param e the failure sshj raised.
 * @return the typed error to throw.
 */
internal fun openFailure(e: Throwable): HcException = when (e) {
    is UserAuthException -> AuthFailed()
    else -> TransportLost("ssh connection or login failed")
}

/** [SshSessionPort] over sshj's [SSHClient]. */
internal class SshjSession(private val client: SSHClient) : SshSessionPort {
    override fun setTimeouts(connectMillis: Int, socketMillis: Int) {
        client.setConnectTimeout(connectMillis)
        client.setTimeout(socketMillis)
    }

    override fun addHostKeyVerifier(verifier: HostKeyVerifier) {
        client.addHostKeyVerifier(verifier)
    }

    override fun connect(host: String, port: Int) {
        client.connect(host, port)
    }

    override fun authPublickey(user: String, seed: ByteArray) {
        client.authPublickey(user, ed25519KeyProvider(seed))
    }

    override fun authPassword(user: String, password: CharArray) {
        client.authPassword(user, password)
    }

    override fun exec(command: String): ExecChannelPort {
        val session = client.startSession()
        return SshjExecChannel(session, session.exec(command))
    }

    override fun disconnect() {
        client.disconnect()
    }
}

/** One sshj exec channel, with its session, behind [ExecChannelPort]. */
private class SshjExecChannel(
    private val session: Session,
    private val command: Session.Command,
) : ExecChannelPort {
    override val stdin: OutputStream get() = command.outputStream
    override val stdout: InputStream get() = command.inputStream
    override val stderr: InputStream get() = command.errorStream

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
    }
}

/** The Ed25519 key provider sshj needs for public-key login, built from the 32-byte seed. */
private fun ed25519KeyProvider(seed: ByteArray): KeyProvider {
    val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    return object : KeyProvider {
        override fun getPrivate(): PrivateKey = Ed25519KeyFactory.getPrivateKey(seed)

        override fun getPublic(): PublicKey = Ed25519KeyFactory.getPublicKey(publicKey)

        override fun getType(): KeyType = KeyType.ED25519
    }
}

/**
 * sshj's host-key hook, delegating every decision to [HostKeyGate]. Returns `true` only for a trusted key.
 *
 * @param gate the gate that decides and records the decision.
 */
private class SshjHostKeyVerifier(private val gate: HostKeyGate) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val type = KeyType.fromKey(key)
        val buffer = Buffer.PlainBuffer()
        type.putPubKeyIntoBuffer(key, buffer)
        return gate.admit(type.toString(), buffer.compactData)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> =
        gate.pinnedAlgorithms().toMutableList()
}

/**
 * One exec channel as a [LinePipe]: one JSON message per line, UTF-8, on the channel's streams. Its stderr is read to the end on
 * a job of its own and discarded, so an undrained stderr cannot fill the channel and wedge hc (phase 6 P0-3). The job is cancelled
 * when the pipe closes.
 *
 * @param channel the running `hc connect` command.
 * @param io the dispatcher for blocking reads and writes.
 */
internal class SshjLinePipe(
    private val channel: ExecChannelPort,
    private val io: CoroutineDispatcher,
) : LinePipe {
    private val reader = BufferedReader(InputStreamReader(channel.stdout, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(channel.stdin, Charsets.UTF_8))
    private val drainScope = CoroutineScope(SupervisorJob() + io)

    /** The job that discards stderr. Visible to the tests, which check it stops on close. */
    internal val stderrDrain: Job = drainScope.launch { drain(channel.stderr) }

    override suspend fun writeLine(line: String) {
        withContext(io) {
            try {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                throw TransportLost("write to hc failed")
            }
        }
    }

    override suspend fun readLine(): String? = withContext(io) {
        try {
            reader.readLine()
        } catch (e: IOException) {
            throw TransportLost("read from hc failed")
        }
    }

    override suspend fun close() {
        withContext(io) {
            drainScope.cancel()
            runCatching { channel.close() }
        }
    }

    /** Reads and discards stderr until it ends or the channel closes. Nothing read here is shown or logged. */
    private fun drain(stream: InputStream) {
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        try {
            while (stream.read(buffer) >= 0) {
                // Discarded on purpose: hc's diagnostics may hold terminal text.
            }
        } catch (e: IOException) {
            // The channel closed: nothing more to drain.
        }
    }

    private companion object {
        /** Size of each stderr read; the bytes are thrown away. */
        const val DRAIN_BUFFER_BYTES: Int = 8 * 1024
    }
}
