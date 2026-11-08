package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.LinePipe
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/**
 * An in-memory [LinePipe] for tests: no socket, no real time.
 *
 * Every line the client writes is recorded in [written] and passed to [onWrite], which may [push] replies.
 * Lines queued with [push] are what the client reads. [end] simulates hc closing the channel.
 *
 * @param onWrite called for each line the client writes; the default does nothing.
 */
class FakeLinePipe(private val onWrite: suspend FakeLinePipe.(String) -> Unit = {}) : LinePipe {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private var closed = false

    /** `true` once [close] has been called. */
    val isClosed: Boolean
        get() = closed

    /** Every line the client wrote, in order. */
    val written: MutableList<String> = mutableListOf()

    override suspend fun writeLine(line: String) {
        if (closed) throw TransportLost("fake pipe is closed")
        assertFalse("a request must be one line", line.contains('\n'))
        written += line
        onWrite(line)
    }

    override suspend fun readLine(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        closed = true
        incoming.close()
    }

    /** Queues one line for the client to read: a message from the far end. */
    fun push(line: String) {
        incoming.trySend(line)
    }

    /** Simulates hc closing the channel: the next read returns `null`. */
    fun end() {
        incoming.close()
    }

    companion object {
        /**
         * A pipe that answers each request from [fixture]. It asserts the request equals the recorded one
         * (ignoring `id`), then replies with [response] (default: the fixture's own response) with `id` set
         * to the client's id.
         *
         * @param fixture the fixture to replay.
         * @param response the reply to send; defaults to the fixture's response.
         * @return the replaying pipe.
         */
        fun replaying(fixture: Fixture, response: JsonObject = fixture.response): FakeLinePipe =
            FakeLinePipe { line ->
                assertRequestMatches(fixture.request, line, fixture.name)
                push(withId(response, requestIdOf(line)))
            }
    }
}

/**
 * Asserts that a line the client wrote equals [expected], after parsing both as JSON and ignoring only `id`.
 *
 * @param expected the recorded request object.
 * @param actualLine the line the client wrote.
 * @param label the fixture name, for the failure message.
 * @throws AssertionError when they differ, so a wrong param fails the calling test.
 */
fun assertRequestMatches(expected: JsonObject, actualLine: String, label: String) {
    val actual = Json.parseToJsonElement(actualLine).jsonObject
    assertEquals("$label: request differs from the recorded request (id ignored)", expected.withoutId(), actual.withoutId())
}

/** The `id` of a request line. */
fun requestIdOf(line: String): Long = Json.parseToJsonElement(line).jsonObject.getValue("id").jsonPrimitive.long

/** Copies [response] with its `id` set to [id], encoded as one compact line. */
fun withId(response: JsonObject, id: Long): String =
    Json.encodeToString(JsonObject.serializer(), JsonObject(response + ("id" to JsonPrimitive(id))))

/** This object without its `id` key. */
fun JsonObject.withoutId(): JsonObject = JsonObject(filterKeys { it != "id" })
