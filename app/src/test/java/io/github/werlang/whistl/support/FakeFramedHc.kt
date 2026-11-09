package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.BytePipe
import io.github.werlang.whistl.core.rpc.Frame
import io.github.werlang.whistl.core.rpc.FrameCodec
import io.github.werlang.whistl.core.rpc.FrameDecoder
import io.github.werlang.whistl.core.rpc.FrameKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The hc side of one framed connection, for tests (SPEC §5.1). It answers the framed `hello` from `hello_framed.json`, serves
 * channel 0 through [commandReply] (so `terminal.attach` answers as the fake core does), and after an attach sends the screen's text
 * as the first paint. [send] carries the program's output on the attached channel, [end] ends the stream as hc does when its channel
 * closes, and the frames the client sends are recorded: [resizes], [credits] and [detaches].
 *
 * @param commandReply answers one request without its id, as the fake core does; `null` for a method it does not serve.
 */
class FakeFramedHc(private val commandReply: (JsonObject) -> JsonObject?) : BytePipe {
    private val pipe = FakeBytePipe { bytes -> onClientWrite(bytes) }
    private val decoder = FrameDecoder()
    private var helloAnswered = false

    /** The channel the last `terminal.attach` was answered with, or `null` before an attach. */
    var channel: Long? = null
        private set

    /** The `terminal.attach` params the client sent, in order. */
    val attaches: MutableList<JsonObject> = mutableListOf()

    /** The sizes the client sent with RESIZE frames, as (cols, rows), in order. */
    val resizes: MutableList<Pair<Int, Int>> = mutableListOf()

    /** The byte counts the client credited with CREDIT frames, in order. */
    val credits: MutableList<Long> = mutableListOf()

    /** How many CLOSE frames the client sent (its detach). */
    var detaches: Int = 0
        private set

    override suspend fun write(bytes: ByteArray) = pipe.write(bytes)

    override suspend fun read(buffer: ByteArray): Int = pipe.read(buffer)

    override suspend fun close() = pipe.close()

    /**
     * Sends [bytes] as the program's output on the attached channel: one DATA frame.
     *
     * @param bytes the program's bytes.
     */
    fun send(bytes: ByteArray) {
        val live = checkNotNull(channel) { "the stream is not attached yet" }
        pipe.push(FrameCodec.data(live, bytes))
    }

    /** Ends the stream as hc does when its channel closes: after the queued bytes, the client reads the end. */
    fun end() {
        pipe.end()
    }

    private suspend fun onClientWrite(bytes: ByteArray) {
        if (!helloAnswered) {
            helloAnswered = true
            val line = String(bytes, Charsets.UTF_8).trimEnd('\n')
            val fixture = Fixtures.load("hello_framed.json")
            pipe.push((withId(fixture.response, requestIdOf(line)) + "\n").toByteArray(Charsets.UTF_8))
            return
        }
        decoder.feed(bytes) { frame -> onFrame(frame) }
    }

    private fun onFrame(frame: Frame) {
        when {
            frame.channel == CONTROL_CHANNEL && frame.kind == FrameKind.DATA ->
                onRequest(String(frame.payload, Charsets.UTF_8))

            frame.kind == FrameKind.CREDIT -> credits += unsigned(frame.payload, 0, 4)

            frame.kind == FrameKind.RESIZE -> resizes += Pair(unsigned(frame.payload, 0, 2).toInt(), unsigned(frame.payload, 2, 2).toInt())

            frame.kind == FrameKind.CLOSE -> detaches++

            else -> Unit
        }
    }

    /** One JSON-RPC request on channel 0: answered as a frame of its own, with no newline (SPEC §5.1). */
    private fun onRequest(line: String) {
        val request = Json.parseToJsonElement(line).jsonObject
        val id = requestIdOf(line)
        val method = request["method"]?.jsonPrimitive?.content
        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
        if (method == "terminal.attach") attaches += params
        val reply = commandReply(request.withoutId()) ?: return
        pipe.push(FrameCodec.data(CONTROL_CHANNEL, withId(reply, id).toByteArray(Charsets.UTF_8)))
        val result = reply["result"]?.jsonObject ?: return
        if (method == "terminal.attach") {
            val live = result.getValue("channel").jsonPrimitive.long
            channel = live
            // A resumed attach carries on from the screen the viewer has: the core sends no first paint (decision(assumed), SPEC §5.1).
            val resumed = params["resumed"]?.jsonPrimitive?.content == "true"
            if (!resumed) pipe.push(FrameCodec.data(live, firstPaint(params)))
        }
    }

    /** The first paint of an attach: the terminal's text, as `terminal.read` gives it, with CR LF line ends. */
    private fun firstPaint(params: JsonObject): ByteArray {
        val address = params.getValue("terminal").jsonPrimitive.content
        val read = JsonObject(
            mapOf(
                "method" to JsonPrimitive("terminal.read"),
                "params" to JsonObject(mapOf("terminal" to JsonPrimitive(address))),
            ),
        )
        val text = commandReply(read)?.get("result")?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        return text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun unsigned(bytes: ByteArray, at: Int, width: Int): Long {
        var value = 0L
        for (i in width - 1 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private companion object {
        /** The JSON-RPC channel. */
        const val CONTROL_CHANNEL: Long = 0L
    }
}
