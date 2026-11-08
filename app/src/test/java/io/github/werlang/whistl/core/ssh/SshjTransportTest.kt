package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.support.FakeHostKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SSH transport's session hygiene (phase 6 P0-3), against a fake of sshj's client: the timeouts are applied before connect,
 * a session is closed whatever ends the open (including a cancellation while connect is blocked), and stderr is drained so a
 * chatty core cannot wedge the channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshjTransportTest {

    /**
     * The fake sshj client. It records each call in order. [blockConnect] makes `connect` wait until `disconnect` is called, as a
     * real connect does when the socket is slow. [authError] is thrown by either login call.
     */
    private class FakeSession(
        private val blockConnect: Boolean = false,
        private val authError: Throwable? = null,
        private val connectTimesOut: Boolean = false,
    ) : SshSessionPort {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val disconnects = AtomicInteger(0)
        val connectEntered = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        var stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        var stderr: InputStream = ByteArrayInputStream(ByteArray(0))

        /** The connect timeout the client was given, or `null` when none was set. */
        private var connectTimeoutMillis: Int? = null

        override fun setTimeouts(connectMillis: Int, socketMillis: Int) {
            connectTimeoutMillis = connectMillis
            calls += "timeouts $connectMillis $socketMillis"
        }

        override fun addHostKeyVerifier(verifier: HostKeyVerifier) {
            calls += "verifier"
        }

        override fun connect(host: String, port: Int) {
            calls += "connect"
            connectEntered.countDown()
            if (connectTimesOut) {
                // sshj gives up with a SocketTimeoutException once the connect timeout set on the client has passed.
                disconnected.await(connectTimeoutMillis?.toLong() ?: (CONNECT_WAIT_SECONDS * 1000), TimeUnit.MILLISECONDS)
                throw SocketTimeoutException("connect timed out")
            }
            if (blockConnect) {
                disconnected.await(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS)
                throw IOException("connect interrupted by disconnect")
            }
        }

        override fun authPublickey(user: String, seed: ByteArray) {
            calls += "auth-key"
            authError?.let { throw it }
        }

        override fun authPassword(user: String, password: CharArray) {
            calls += "auth-password"
            authError?.let { throw it }
        }

        override fun exec(command: String): ExecChannelPort {
            calls += "exec"
            return FakeChannel(stdout, stderr)
        }

        override fun disconnect() {
            calls += "disconnect"
            disconnects.incrementAndGet()
            disconnected.countDown()
        }
    }

    /** The fake exec channel: fixed stdout, and the stderr the test supplies. */
    private class FakeChannel(
        override val stdout: InputStream,
        override val stderr: InputStream,
    ) : ExecChannelPort {
        override val stdin: OutputStream = ByteArrayOutputStream()

        override fun close() {
            (stderr as? ProducingStream)?.close()
        }
    }

    /**
     * A stderr stream that produces [total] bytes, then stays open (as a quiet channel does) until it is closed. [onConsumed] runs
     * once every byte has been read, so a test can wait for the drain.
     */
    private class ProducingStream(
        private val total: Int,
        private val onConsumed: () -> Unit,
    ) : InputStream() {
        private var produced = 0
        private val closed = CountDownLatch(1)

        override fun read(): Int = throw UnsupportedOperationException("the drain reads blocks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced < total) {
                val n = minOf(len, total - produced)
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                produced += n
                if (produced == total) onConsumed()
                return n
            }
            closed.await()
            throw IOException("channel closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    /**
     * A transport over [session], with the given timeouts (the defaults unless a test sets them) and dispatcher for the blocking
     * calls.
     *
     * @param session the fake client.
     * @param io the dispatcher for blocking calls.
     * @param timeouts the connect and socket timeouts.
     * @return the transport.
     */
    private fun transport(
        session: FakeSession,
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        timeouts: SshTimeouts = SshTimeouts(),
    ): SshjTransport =
        SshjTransport(
            target = SshTarget("m1", "m1.lan", 22, "ci"),
            auth = SshAuth.Key(ByteArray(32)),
            gate = HostKeyGate(HostKeyPolicy(FakeHostKeyStore()), "m1"),
            io = io,
            timeouts = timeouts,
            newSession = { session },
        )

    /** The timeouts reach the client before the connect, and the defaults are 15 s to connect and 30 s on the socket. */
    @Test
    fun theTimeoutsAreAppliedBeforeConnect() = runTest {
        val session = FakeSession()

        transport(session).open()

        assertEquals(15_000, SshTimeouts().connectMillis)
        assertEquals(30_000, SshTimeouts().socketMillis)
        val timeouts = session.calls.indexOf("timeouts 15000 30000")
        assertTrue("the timeouts were set", timeouts >= 0)
        assertTrue("before the host-key check is installed", timeouts < session.calls.indexOf("verifier"))
        assertTrue("and before the connect", timeouts < session.calls.indexOf("connect"))
    }

    /** A refused login closes the session and is AuthFailed, which is not retried. */
    @Test
    fun aRefusedLoginClosesTheSessionAndIsAuthFailed() = runTest {
        val session = FakeSession(authError = UserAuthException("refused"))

        try {
            transport(session).open()
            fail("a refused login must not open")
        } catch (e: AuthFailed) {
            // expected
        }

        assertTrue("the session was closed", session.disconnects.get() >= 1)
    }

    /** Any failure after the connect, not only a refused login, closes the session before the error goes on. */
    @Test
    fun anUnexpectedFailureAfterConnectClosesTheSession() = runTest {
        val session = FakeSession(authError = IllegalStateException("login path broke"))

        try {
            transport(session).open()
            fail("the failure must surface")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertTrue("the session was closed", session.disconnects.get() >= 1)
    }

    /**
     * A cancellation while connect is blocked closes the session, so the blocked connect returns and nothing stays authenticated.
     * This used to leak: the transport was opened outside the connector's try, and a cancel did not reach a blocked connect.
     */
    @Test
    fun cancellingWhileConnectIsBlockedClosesTheSession() {
        val session = FakeSession(blockConnect = true)
        val transport = transport(session, io = Dispatchers.IO)
        var cancelled = false

        runBlocking(Dispatchers.Default) {
            val opening = launch {
                try {
                    transport.open()
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            withContext(Dispatchers.IO) { session.connectEntered.await(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS) }
            opening.cancel()
            opening.join()
        }

        assertTrue("the open was cancelled", cancelled)
        assertTrue("the blocked connect was closed", session.disconnects.get() >= 1)
    }

    /**
     * hc's stderr is read while the channel is open, so a core that writes a megabyte of diagnostics does not block its stdout:
     * the stdout line still arrives, the stderr is consumed, and the drain stops when the pipe closes.
     */
    @Test
    fun stderrIsDrainedSoStdoutStillFlowsAndTheDrainStopsOnClose() = runTest {
        val consumed = CountDownLatch(1)
        val stderr = ProducingStream(total = ONE_MIB) { consumed.countDown() }
        val reply = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        val session = FakeSession().also {
            it.stdout = ByteArrayInputStream("$reply\n".toByteArray())
            it.stderr = stderr
        }
        val transport = transport(session, io = Dispatchers.IO)
        transport.open()

        val pipe = transport.exec(SshTransport.HC_CONNECT_COMMAND) as SshjLinePipe
        assertTrue("the whole megabyte of stderr was read", consumed.await(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(reply, pipe.readLine())

        val drain = pipe.stderrDrain
        pipe.close()
        // A drain that merely ended on the closed stream completes normally; only close() cancelling it makes this true.
        assertTrue("close cancelled the drain job", drain.isCancelled)
    }

    /**
     * A connect that never completes ends at the connect timeout. The client gives up with a timeout, so `open()` fails with a
     * transport error in bounded time and the machine leaves `Connecting` (phase 6 P0-3). The fake waits for the timeout the
     * client was given, not for a fallback: if the timeouts were never applied it would wait [CONNECT_WAIT_SECONDS], and the
     * elapsed-time check would fail.
     */
    @Test
    fun aConnectThatNeverCompletesEndsAtTheConnectTimeout() {
        val session = FakeSession(connectTimesOut = true)
        val transport = transport(session, io = Dispatchers.IO, timeouts = SshTimeouts(connectMillis = 200, socketMillis = 200))
        val started = System.nanoTime()

        val failure = runBlocking(Dispatchers.Default) {
            withTimeout(CONNECT_WAIT_SECONDS * 1000) {
                try {
                    transport.open()
                    null
                } catch (e: TransportLost) {
                    e
                }
            }
        }

        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the open failed with a transport error", failure is TransportLost)
        assertTrue("the open gave up at the connect timeout (${elapsedMillis} ms)", elapsedMillis < CONNECT_WAIT_SECONDS * 1000)
        assertTrue("the session was closed", session.disconnects.get() >= 1)
    }

    private companion object {
        /** A real-time bound on a wait for another thread. It is never reached when the code under test works. */
        const val CONNECT_WAIT_SECONDS: Long = 5L

        /** One mebibyte of stderr: more than a pipe buffer. */
        const val ONE_MIB: Int = 1 shl 20
    }
}
