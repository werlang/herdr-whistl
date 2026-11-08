package io.github.werlang.whistl.core.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Default timeout for one call, in milliseconds. Choice: 30 s; SPEC §5 gives no figure. */
const val DEFAULT_CALL_TIMEOUT_MS: Long = 30_000L

/**
 * Idle window for the events channel, in milliseconds. SPEC §5: hc sends a heartbeat every 15 s, so 40 s without
 * any line means the connection is dead.
 */
const val EVENTS_IDLE_TIMEOUT_MS: Long = 40_000L

/**
 * A notification from hc: a line with a `method` and no `id`. Its `params` are kept raw for the caller to decode.
 *
 * @property method the notification name, for example `event`.
 * @property params the raw `params` element.
 */
data class RpcNotification(val method: String, val params: JsonElement)

/**
 * JSON-RPC 2.0 over one [LinePipe] (SPEC §5).
 *
 * Behaviour:
 * - Request ids start at 1 and increase by one per call. Ids are never reused on this connection.
 * - Every request is one JSON object on one line, never pretty-printed.
 * - A response completes the pending call with the same id. A response with `error` fails it with the mapped
 *   [HcException] (see [RpcErrorWire.toException]). A response with an unknown id is dropped.
 * - A notification (no `id`) is emitted on [notifications], in arrival order.
 * - When the pipe closes, every pending call fails with [TransportLost] and [notifications] ends with it.
 * - With [idleTimeoutMillis] set, no line for that long is also a [TransportLost] (the events watchdog).
 * - A line that is not a JSON object is a protocol error: the connection fails with [TransportLost]. The line's
 *   text is not put in the message, so terminal text never reaches an exception.
 *
 * @param pipe the channel to read from and write to.
 * @param scope the scope the reader coroutine runs in. Tests pass `backgroundScope`.
 * @param idleTimeoutMillis the watchdog window, or `null` for no watchdog (commands).
 */
class JsonRpcConnection(
    private val pipe: LinePipe,
    scope: CoroutineScope,
    private val idleTimeoutMillis: Long? = null,
) {
    private val nextId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)
    private val notificationChannel = Channel<RpcNotification>(Channel.UNLIMITED)

    /** Notifications from hc in arrival order. Ends with [TransportLost] when the connection fails. */
    val notifications: Flow<RpcNotification> = notificationChannel.receiveAsFlow()

    // Declared last: the reader must see every field above initialised.
    private val reader = scope.launch { readLoop() }

    /**
     * Sends one request and waits for its response.
     *
     * @param method the RPC method name.
     * @param params the params object, already encoded.
     * @param timeoutMillis how long the write and the wait for the reply may take together, in virtual or real time.
     * @return the response's `result` element.
     * @throws HcException the mapped error from hc, [TransportLost], or [Timeout] when no reply arrives in time.
     */
    suspend fun call(
        method: String,
        params: JsonElement,
        timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): JsonElement {
        if (closed.get()) throw TransportLost("connection is closed")
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            // A failure between the check above and the insert would leave this call waiting: check again.
            if (closed.get()) throw TransportLost("connection is closed")
            val line = RequestJson.encodeToString(
                RpcRequest.serializer(),
                RpcRequest(id = id, method = method, params = params),
            )
            // The write and the wait share one window. A write that never completes must not hold the call, or the write
            // lock that every other call on this connection waits for (phase 6 P0-7).
            return withTimeout(timeoutMillis) {
                writeLock.withLock { pipe.writeLine(line) }
                reply.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw Timeout(method, timeoutMillis)
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Closes the connection. Pending calls and [notifications] fail with [TransportLost].
     */
    suspend fun close() {
        failAll(TransportLost("connection closed by the client"))
        pipe.close()
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = readLineWithWatchdog() ?: break
                handleLine(line)
            }
            failAll(TransportLost("channel closed by hc"))
        } catch (e: HcException) {
            failAll(e)
        } catch (e: CancellationException) {
            failAll(TransportLost("reader stopped"))
            throw e
        } catch (e: Throwable) {
            failAll(TransportLost("read failed", e))
        }
        runCatching { pipe.close() }
    }

    /** Reads one line; with a watchdog, no line within the window fails the connection. */
    private suspend fun readLineWithWatchdog(): String? {
        val idle = idleTimeoutMillis ?: return pipe.readLine()
        try {
            return withTimeout(idle) { pipe.readLine() }
        } catch (e: TimeoutCancellationException) {
            throw TransportLost("no line from hc for $idle ms")
        }
    }

    private fun handleLine(line: String) {
        val envelope = try {
            ResponseJson.decodeFromString(RpcEnvelope.serializer(), line)
        } catch (e: SerializationException) {
            throw TransportLost("malformed line from hc")
        } catch (e: IllegalArgumentException) {
            throw TransportLost("malformed line from hc")
        }
        val id = envelope.id
        if (id != null) {
            val reply = pending.remove(id) ?: return
            val error = envelope.error
            if (error != null) {
                reply.completeExceptionally(error.toException())
            } else {
                reply.complete(envelope.result ?: JsonNull)
            }
        } else {
            val method = envelope.method ?: return
            notificationChannel.trySend(RpcNotification(method, envelope.params ?: JsonNull))
        }
    }

    /** Marks the connection closed once and fails everything waiting on it. */
    private fun failAll(cause: HcException) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        notificationChannel.close(cause)
    }
}
