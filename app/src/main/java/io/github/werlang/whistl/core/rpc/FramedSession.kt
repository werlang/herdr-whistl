package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.HelloResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The raw byte stream of one exec channel, once the connection is framed (SPEC §5.1). Reads and writes carry frames only:
 * the line-mode exchange ends with the `hello` reply.
 */
interface BytePipe {
    /** Writes all of [bytes]. Throws `HcException.TransportLost` when the channel is already gone. */
    suspend fun write(bytes: ByteArray)

    /**
     * Reads up to `buffer.size` bytes into [buffer].
     *
     * @return the count read, or `-1` when the far end has closed the channel.
     */
    suspend fun read(buffer: ByteArray): Int

    /** Closes the channel. Safe to call more than once. */
    suspend fun close()
}

/** What arrives on a channel: the program's bytes, or the end of the channel (SPEC §5.1). */
sealed interface ChannelEvent {
    /** Bytes from the program: the first paint, then live output. Credit them with [FramedChannel.credit] once handled. */
    class Data(val bytes: ByteArray) : ChannelEvent

    /**
     * The channel ended. [exit] holds the core's facts when the program ended (`exit_code`, `finished_ms`, `reason`,
     * `signal`); it is `null` when the client detached.
     */
    class Closed(val exit: JsonObject?) : ChannelEvent
}

/**
 * A framed connection (SPEC §5.1): channel 0 carries the JSON-RPC messages, and every other channel carries one terminal's
 * stream. The reader runs on [scope]. When the stream ends or breaks, every pending call and every channel fails with the
 * same [HcException].
 *
 * Frames for a channel may arrive before [channel] is called for it: they wait in that channel's queue. A frame the client
 * does not know, or one the core does not send, is skipped and counted in [skippedFrames]; its payload is not kept.
 *
 * @param pipe the byte stream, after the line-mode `hello` (see [open]).
 * @param scope the scope for the reader. Tests pass `backgroundScope`.
 * @param hello what the core answered to the framed `hello`.
 * @param leftover the bytes that followed the `hello` reply's newline in the same read: the first frames.
 */
class FramedSession private constructor(
    private val pipe: BytePipe,
    scope: CoroutineScope,
    val hello: HelloResult,
    leftover: ByteArray,
) {
    private val decoder = FrameDecoder()
    private val writeLock = Mutex()
    private val rpcLines = Channel<String>(Channel.UNLIMITED)
    private val queues = ConcurrentHashMap<Long, Channel<ChannelEvent>>()
    private val skipped = AtomicLong(0L)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var failure: HcException? = null

    /**
     * The JSON-RPC connection over channel 0. Its ids start at 2, because the framed `hello` was id 1.
     */
    val rpc: JsonRpcConnection = JsonRpcConnection(RpcPipe(), scope, firstId = FIRST_RPC_ID)

    /** Frames skipped so far: a kind this client does not know, or one the core does not send. */
    val skippedFrames: Long
        get() = skipped.get()

    // Declared last: the reader must see every field above initialised.
    private val reader = scope.launch { readLoop(leftover) }

    /**
     * The channel [id]: its events, and the frames the viewer sends to it.
     *
     * @param id the channel number from the `terminal.attach` reply.
     */
    fun channel(id: Long): FramedChannel = FramedChannel(this, id, queueOf(id))

    /** Ends the session. Pending calls and every channel's events fail with [TransportLost]. */
    suspend fun close() {
        fail(TransportLost("connection closed by the client"))
        pipe.close()
    }

    /** Writes one whole frame. Writes are serialised, so two frames never interleave. */
    internal suspend fun send(frame: ByteArray) {
        if (closed.get()) throw TransportLost("connection is closed")
        try {
            writeLock.withLock { pipe.write(frame) }
        } catch (e: HcException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw TransportLost("write failed", e)
        }
    }

    private fun queueOf(id: Long): Channel<ChannelEvent> = queues.computeIfAbsent(id) {
        Channel<ChannelEvent>(Channel.UNLIMITED).also { queue -> failure?.let { queue.close(it) } }
    }

    private suspend fun readLoop(leftover: ByteArray) {
        try {
            decoder.feed(leftover) { dispatch(it) }
            val buffer = ByteArray(READ_BYTES)
            while (true) {
                val count = pipe.read(buffer)
                if (count < 0) break
                decoder.feed(buffer, 0, count) { dispatch(it) }
            }
            decoder.finish()
            fail(TransportLost("channel closed by hc"))
        } catch (e: HcException) {
            fail(e)
        } catch (e: CancellationException) {
            fail(TransportLost("reader stopped"))
            throw e
        } catch (e: Throwable) {
            fail(TransportLost("read failed", e))
        }
        runCatching { pipe.close() }
    }

    /** Routes one frame: channel 0 to the JSON-RPC reader; DATA and CLOSE from the core to their channel's queue. */
    private fun dispatch(frame: Frame) {
        when {
            frame.channel == RPC_CHANNEL && frame.kind == FrameKind.DATA ->
                rpcLines.trySend(String(frame.payload, Charsets.UTF_8))
            frame.channel == RPC_CHANNEL -> skipped.incrementAndGet()
            frame.kind == FrameKind.DATA -> queueOf(frame.channel).trySend(ChannelEvent.Data(frame.payload))
            frame.kind == FrameKind.CLOSE ->
                queueOf(frame.channel).trySend(ChannelEvent.Closed(exitFacts(frame.payload)))
            else -> skipped.incrementAndGet()
        }
    }

    /** The facts a CLOSE from the core carries, or `null` when it carries none (the client's own detach). */
    private fun exitFacts(payload: ByteArray): JsonObject? {
        if (payload.isEmpty()) return null
        return try {
            ResponseJson.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Marks the session failed once and fails everything waiting on it. Later channels start failed too. */
    private fun fail(cause: HcException) {
        if (!closed.compareAndSet(false, true)) return
        failure = cause
        rpcLines.close(cause)
        queues.values.forEach { it.close(cause) }
    }

    /** Channel 0 as a line pipe: each JSON-RPC message is one DATA frame, without its newline. */
    private inner class RpcPipe : LinePipe {
        override suspend fun writeLine(line: String) {
            send(FrameCodec.data(RPC_CHANNEL, line.toByteArray(Charsets.UTF_8)))
        }

        override suspend fun readLine(): String? = rpcLines.receiveCatching().getOrNull()

        override suspend fun close() {
            this@FramedSession.close()
        }
    }

    companion object {
        /** The JSON-RPC channel (SPEC §5.1). */
        private const val RPC_CHANNEL = 0L

        /** The id of the framed `hello`; the JSON-RPC calls after it start at [FIRST_RPC_ID]. */
        private const val HELLO_ID = 1L

        private const val FIRST_RPC_ID = 2L

        /** How much one read asks the pipe for. */
        private const val READ_BYTES = 64 * 1024

        private const val NEWLINE = '\n'.code.toByte()

        /**
         * Sends `hello {framed: true}` on [pipe] and starts the framed session (SPEC §5.1). The reply must be one line that
         * says `framed: true`. Bytes after its newline are the first frames; they are kept for the decoder.
         *
         * @param pipe the byte stream of a connection that has not said `hello` yet.
         * @param scope the scope for the session's reader.
         * @return the framed session.
         * @throws NeedsFramed when the core does not switch the connection to framed mode.
         * @throws UnsupportedGeneration when the core speaks another protocol generation.
         * @throws TransportLost when the channel ends before the reply, or the reply is not a line of JSON.
         */
        suspend fun open(pipe: BytePipe, scope: CoroutineScope): FramedSession {
            val request = RpcRequest(
                id = HELLO_ID,
                method = "hello",
                params = RequestJson.encodeToJsonElement(FramedHelloParams(framed = true)),
            )
            val line = RequestJson.encodeToString(RpcRequest.serializer(), request) + "\n"
            pipe.write(line.toByteArray(Charsets.UTF_8))
            val (reply, leftover) = readHelloReply(pipe)
            val envelope = try {
                ResponseJson.decodeFromString(RpcEnvelope.serializer(), reply)
            } catch (e: SerializationException) {
                throw TransportLost("malformed line from hc")
            } catch (e: IllegalArgumentException) {
                throw TransportLost("malformed line from hc")
            }
            envelope.error?.let { throw it.toException() }
            val wire = ResponseJson.decodeFromJsonElement(HelloWire.serializer(), envelope.result ?: JsonNull)
            if (!wire.framed) throw NeedsFramed("hello did not switch the connection to framed mode", null)
            if (wire.generation != SUPPORTED_GENERATION) throw UnsupportedGeneration(wire.generation)
            val hello = HelloResult(
                coreId = wire.core.id,
                coreName = wire.core.name,
                features = wire.features,
                generation = wire.generation,
                methods = wire.methods,
                version = wire.version,
            )
            return FramedSession(pipe, scope, hello, leftover)
        }

        /** The protocol generation this client speaks (SPEC §5). */
        private const val SUPPORTED_GENERATION = 1

        /** Reads up to the hello reply's newline. Returns the reply without it, and the bytes that followed it. */
        private suspend fun readHelloReply(pipe: BytePipe): Pair<String, ByteArray> {
            val line = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BYTES)
            while (true) {
                val count = pipe.read(buffer)
                if (count < 0) throw TransportLost("channel closed before the hello reply")
                val newline = (0 until count).firstOrNull { buffer[it] == NEWLINE }
                if (newline == null) {
                    line.write(buffer, 0, count)
                    continue
                }
                line.write(buffer, 0, newline)
                return Pair(String(line.toByteArray(), Charsets.UTF_8), buffer.copyOfRange(newline + 1, count))
            }
        }
    }
}

/**
 * One channel of a framed session (SPEC §5.1): what the program sends, and the frames the viewer sends to it.
 *
 * @property id the channel number from the `terminal.attach` reply.
 */
class FramedChannel internal constructor(
    private val session: FramedSession,
    val id: Long,
    private val queue: Channel<ChannelEvent>,
) {
    /**
     * The channel's events, in order: [ChannelEvent.Data], then [ChannelEvent.Closed]. The flow fails with the session's
     * failure when the connection breaks.
     */
    val events: Flow<ChannelEvent> = queue.receiveAsFlow()

    /** Sends keys or pasted bytes to the program, as DATA. */
    suspend fun write(bytes: ByteArray) {
        session.send(FrameCodec.data(id, bytes))
    }

    /**
     * Sends the viewer's size in cells (RESIZE).
     *
     * @throws IllegalArgumentException when a size is not a u16.
     */
    suspend fun resize(cols: Int, rows: Int) {
        session.send(FrameCodec.resize(id, cols, rows))
    }

    /** Sends focus in or focus out (FOCUS). The core forwards it only to a program that asked for focus reports. */
    suspend fun focus(focused: Boolean) {
        session.send(FrameCodec.focus(id, focused))
    }

    /** Sends the viewer's colours and cell size (FACTS). */
    suspend fun facts(display: ViewerDisplay) {
        session.send(FrameCodec.facts(id, display))
    }

    /**
     * Acknowledges [bytes] of DATA the client has handled (CREDIT). Send it after handling each [ChannelEvent.Data].
     *
     * @throws IllegalArgumentException when [bytes] is negative.
     */
    suspend fun credit(bytes: Int) {
        require(bytes >= 0) { "credit is not negative" }
        session.send(FrameCodec.credit(id, bytes.toLong()))
    }

    /** Detaches from the channel (CLOSE, empty payload). The core answers with its own CLOSE, then the events end. */
    suspend fun detach() {
        session.send(FrameCodec.close(id))
    }
}
