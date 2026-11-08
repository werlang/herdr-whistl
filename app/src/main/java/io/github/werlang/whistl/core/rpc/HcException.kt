package io.github.werlang.whistl.core.rpc

/**
 * Failures from the protocol core, mapped from JSON-RPC errors and transport events.
 *
 * Errors from hc carry `error.data.kind`, a stable string (SPEC §5). Known kinds map to a subclass; any other
 * kind becomes [Unknown]. `hint` is hc's suggested next step, kept as sent; it may be `null`.
 *
 * @property hint hc's hint for the user or the caller, when hc sent one.
 */
sealed class HcException(
    message: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** `kind == "not_found"`: the named terminal, document or machine does not exist. */
class NotFound(message: String, hint: String?) : HcException(message, hint)

/** `kind == "conflict"`: a compare-and-set failed (for example a stale `doc.set` version). Retry after reading. */
class Conflict(message: String, hint: String?) : HcException(message, hint)

/**
 * `kind == "invalid_request"`, or the JSON-RPC standard code `-32600`: hc rejected the request's shape.
 *
 * The kind string is an assumption: no fixture records it, so the code is the fallback.
 */
class InvalidRequest(message: String, hint: String?) : HcException(message, hint)

/**
 * The connection is gone: the channel closed, no line arrived within the watchdog window, a line was not JSON,
 * or the caller closed it. Every pending call fails with this.
 */
class TransportLost(message: String, cause: Throwable? = null) : HcException(message, null, cause)

/** No reply to a call within its timeout. The request may still have run on hc. */
class Timeout(method: String, timeoutMillis: Long) :
    HcException("no reply to $method within $timeoutMillis ms")

/**
 * The SSH login was refused: a wrong key, a wrong password, or a method the server does not accept. It is not
 * retried automatically. The message names neither the user, the host nor any credential.
 */
class AuthFailed : HcException("ssh login was refused")

/** `hello` reported a protocol generation other than 1. The app refuses to talk to that core. */
class UnsupportedGeneration(val generation: Int) :
    HcException("hc protocol generation $generation is not supported; this app needs generation 1")

/**
 * Any other error from hc. Keeps the raw [kind] and JSON-RPC [code] so nothing is lost.
 *
 * @property kind `error.data.kind` as sent, or `null` when hc sent none.
 * @property code the JSON-RPC error code.
 */
class Unknown(val kind: String?, val code: Int, message: String, hint: String?) : HcException(message, hint)
