package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.HcException
import net.schmizz.sshj.common.SSHRuntimeException
import java.io.IOException
import java.net.ConnectException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException

/** `ECONNREFUSED` on Linux and Android. Compared as a number, so no message text is read. */
const val ECONNREFUSED_ERRNO: Int = 111

/**
 * Where a failed connection attempt failed, as the user sees it (phase 7 r1c).
 *
 * - [Unreachable]: a network or transport failure (no route, unknown host, timeout, a dropped connection, a login that
 *   never completed). The app keeps retrying.
 * - [Refused]: the host answered and nothing listens on the port. Not retried automatically.
 * - [Internal]: the failure happened inside the app (a missing algorithm, a provider error, an sshj configuration error,
 *   an `Error`). Not retried automatically; the message names the app, not the network.
 */
enum class FailureStage { Unreachable, Refused, Internal }

/**
 * Classifies a failed attempt by its cause chain: by exception types and errno, never by message text.
 *
 * Internal wins over the rest: sshj wraps a missing algorithm in an `IOException` (`TransportException`), so the chain is
 * searched for an `Error`, a non-transport `RuntimeException` (including sshj's `SSHRuntimeException`), or a
 * `GeneralSecurityException` before any network type is considered. Refused is a `ConnectException` whose errno is
 * `ECONNREFUSED`. Everything else is unreachable.
 *
 * @param e the failure to classify.
 * @param errnoOf the errno of a connect failure, or `null` when it has none. Production reads the Android
 *   `ErrnoException` (see [androidErrno]); tests pass a fake.
 * @return the stage.
 */
internal fun failureStage(e: Throwable, errnoOf: (Throwable) -> Int? = ::androidErrno): FailureStage {
    val chain = causeChain(e)
    if (chain.any { it.isInternal() }) return FailureStage.Internal
    if (chain.any { it is ConnectException && errnoOf(it) == ECONNREFUSED_ERRNO }) return FailureStage.Refused
    return FailureStage.Unreachable
}

/**
 * The class names of the cause chain, joined with ` <- `. Class names only: no message, no host, no user.
 *
 * @param e the failure.
 * @return for example `TransportException <- SSHRuntimeException <- NoSuchAlgorithmException`.
 */
internal fun classChain(e: Throwable): String = causeChain(e).joinToString(" <- ") { it.javaClass.simpleName }

/**
 * The failure and its causes, outermost first. Stops at a cycle and after a fixed depth.
 *
 * @param e the failure.
 * @return the chain.
 */
private fun causeChain(e: Throwable): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var t: Throwable? = e
    while (t != null && chain.size < MAX_CHAIN && chain.none { it === t }) {
        chain += t
        t = t.cause
    }
    return chain
}

/** Whether this link is a failure of the app itself, not of the network. */
private fun Throwable.isInternal(): Boolean = when (this) {
    is HcException, is CancellationException -> false
    is IOException -> false
    is GeneralSecurityException, is SSHRuntimeException -> true
    is Error, is RuntimeException -> true
    else -> false
}

/**
 * The errno behind a connect failure on Android: the [android.system.ErrnoException] that libcore attaches as the cause.
 *
 * @param t the connect failure.
 * @return the errno, or `null` when there is none.
 */
internal fun androidErrno(t: Throwable): Int? = (t.cause as? android.system.ErrnoException)?.errno

private const val MAX_CHAIN: Int = 12
