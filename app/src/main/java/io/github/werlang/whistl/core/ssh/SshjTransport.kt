package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.LinePipe
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.PrivateKey
import java.security.PublicKey

/**
 * [SshTransport] over sshj (SPEC §5, §6). One SSH session per machine: [open] connects, verifies the host key through
 * [HostKeyGate], and authenticates. Each [exec] opens a new channel on that session and runs [HC_CONNECT_COMMAND].
 *
 * Not covered by JVM tests: they open no socket. Only the pure parts (keys, fingerprints, host-key decisions) are.
 *
 * Blocking reads run on [io]. A read cannot be cancelled while blocked; the events watchdog therefore fails the
 * connection, and the caller then closes the pipe, which ends the read.
 *
 * @param target the machine to reach.
 * @param auth how to log in.
 * @param gate the host-key check for this machine.
 * @param io the dispatcher for blocking SSH calls.
 */
class SshjTransport(
    override val target: SshTarget,
    private val auth: SshAuth,
    private val gate: HostKeyGate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SshTransport {
    @Volatile
    private var session: SSHClient? = null

    /**
     * Connects, verifies the host key and authenticates. A second call does nothing.
     *
     * @throws HostKeyRefused when the presented host key was not trusted (see [gate]).
     * @throws TransportLost when the connection or the login fails for another reason.
     */
    override suspend fun open() {
        if (session != null) return
        withContext(io) {
            val client = SSHClient()
            client.addHostKeyVerifier(SshjHostKeyVerifier(gate))
            try {
                client.connect(target.host, target.port)
                when (val credentials = auth) {
                    is SshAuth.Key -> client.authPublickey(target.user, ed25519KeyProvider(credentials.seedBytes()))
                    is SshAuth.Password -> client.authPassword(target.user, credentials.passwordChars())
                }
                session = client
            } catch (e: IOException) {
                runCatching { client.disconnect() }
                gate.lastDecision?.takeUnless { it is HostKeyDecision.Trusted }?.let { decision ->
                    throw HostKeyRefused(decision, gate.lastPresented.takeIf { decision is HostKeyDecision.Unknown })
                }
                throw openFailure(e)
            }
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
            val client = session ?: throw TransportLost("ssh session is not open")
            try {
                val channelSession = client.startSession()
                val channel = channelSession.exec(command)
                SshjLinePipe(channelSession, channel, io)
            } catch (e: IOException) {
                throw TransportLost("could not start hc connect")
            }
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

    private fun ed25519KeyProvider(seed: ByteArray): KeyProvider {
        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = Ed25519KeyFactory.getPrivateKey(seed)

            override fun getPublic(): PublicKey = Ed25519KeyFactory.getPublicKey(publicKey)

            override fun getType(): KeyType = KeyType.ED25519
        }
    }
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
 * One exec channel as a [LinePipe]: one JSON message per line, UTF-8, on the channel's streams.
 *
 * @param session the SSH session the channel belongs to; closed with the channel.
 * @param channel the running `hc connect` command.
 * @param io the dispatcher for blocking reads and writes.
 */
private class SshjLinePipe(
    private val session: Session,
    private val channel: Session.Command,
    private val io: CoroutineDispatcher,
) : LinePipe {
    private val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(channel.outputStream, Charsets.UTF_8))

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
            runCatching { channel.close() }
            runCatching { session.close() }
        }
    }
}
