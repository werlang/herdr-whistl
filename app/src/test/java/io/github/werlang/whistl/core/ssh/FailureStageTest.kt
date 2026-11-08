package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.TransportLost
import net.schmizz.sshj.common.SSHRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException

/**
 * [failureStage] classifies by cause type and errno, never by message (phase 7 r1c). The errno is passed in, so these run on
 * the JVM; production reads the Android `ErrnoException` through [androidErrno].
 */
class FailureStageTest {
    private val noErrno: (Throwable) -> Int? = { null }
    private val refused: (Throwable) -> Int? = { ECONNREFUSED_ERRNO }
    private val networkUnreachable: (Throwable) -> Int? = { 101 } // ENETUNREACH

    /** The key-exchange failure as sshj raises it: a `TransportException` (an IOException) over an SSHRuntimeException. */
    private fun sshjMissingAlgorithm(): Throwable = IOException("key exchange failed").apply {
        initCause(SSHRuntimeException("key exchange failed").apply { initCause(NoSuchAlgorithmException("X25519")) })
    }

    @Test
    fun aMissingAlgorithmIsInternal() {
        assertEquals(FailureStage.Internal, failureStage(NoSuchAlgorithmException("X25519")))
    }

    @Test
    fun theSshjKeyExchangeFailureIsInternalEvenThroughItsIoExceptionWrapper() {
        assertEquals(FailureStage.Internal, failureStage(TransportLost("ssh connection or login failed", sshjMissingAlgorithm())))
    }

    @Test
    fun anIllegalStateIsInternal() {
        assertEquals(FailureStage.Internal, failureStage(IllegalStateException("login path broke")))
    }

    @Test
    fun anErrorIsInternal() {
        assertEquals(FailureStage.Internal, failureStage(NoClassDefFoundError("stripped class")))
    }

    @Test
    fun aDeepInternalCauseWinsOverAConnectRefusal() {
        val refusedWithInternalCause = ConnectException("refused").apply { initCause(NoSuchAlgorithmException("x")) }
        assertEquals(FailureStage.Internal, failureStage(refusedWithInternalCause, refused))
    }

    @Test
    fun unknownHostNoRouteAndTimeoutAreUnreachable() {
        assertEquals(FailureStage.Unreachable, failureStage(UnknownHostException("homelab")))
        assertEquals(FailureStage.Unreachable, failureStage(NoRouteToHostException("no route")))
        assertEquals(FailureStage.Unreachable, failureStage(SocketTimeoutException("timed out")))
    }

    @Test
    fun aConnectFailureWithErrnoEconnrefusedIsRefused() {
        assertEquals(FailureStage.Refused, failureStage(ConnectException("any text"), refused))
    }

    @Test
    fun aConnectFailureWithAnotherErrnoIsUnreachable() {
        assertEquals(FailureStage.Unreachable, failureStage(ConnectException("any text"), networkUnreachable))
    }

    @Test
    fun aConnectFailureWithNoErrnoIsUnreachable() {
        assertEquals(FailureStage.Unreachable, failureStage(ConnectException("refused"), noErrno))
    }

    @Test
    fun aTransportLostWithoutCauseIsUnreachable() {
        assertEquals(FailureStage.Unreachable, failureStage(TransportLost("open failed")))
    }

    @Test
    fun anAuthFailureIsNotAnInternalFailure() {
        assertEquals(FailureStage.Unreachable, failureStage(AuthFailed()))
    }

    @Test
    fun classChainHoldsClassNamesOnlyNoMessages() {
        val failure = TransportLost("ssh connection or login failed", IOException("homelab 100.120.52.26 root").apply {
            initCause(SSHRuntimeException("secret message").apply { initCause(NoSuchAlgorithmException("X25519 for BC")) })
        })
        assertEquals(
            "TransportLost <- IOException <- SSHRuntimeException <- NoSuchAlgorithmException",
            classChain(failure),
        )
    }
}
