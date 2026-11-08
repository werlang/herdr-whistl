package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.CoreMachine
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.requestIdOf
import io.github.werlang.whistl.support.withId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsonRpcConnection] on its own: ids, the one-line framing, notification routing, error mapping, and the
 * failure paths. Each test replays messages through a [FakeLinePipe]; time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcConnectionTest {

    /** Ids start at 1 and increase by one per call. */
    @Test
    fun requestIdsAreMonotonicFromOne() = runTest {
        val pipe = FakeLinePipe { line -> push(withId(emptyResult(), requestIdOf(line))) }
        val connection = JsonRpcConnection(pipe, backgroundScope)

        repeat(3) { connection.call("terminal.list", JsonObject(emptyMap())) }

        assertEquals(listOf(1L, 2L, 3L), pipe.written.map { requestIdOf(it) })
    }

    /** Each request is one compact JSON object on one line, even when a param holds a newline. */
    @Test
    fun eachRequestIsOneJsonObjectOnOneLine() = runTest {
        val pipe = FakeLinePipe { line -> push(withId(emptyResult(), requestIdOf(line))) }
        val connection = JsonRpcConnection(pipe, backgroundScope)

        connection.call(
            "terminal.send",
            JsonObject(mapOf<String, JsonElement>("terminal" to JsonPrimitive("t15s"), "text" to JsonPrimitive("a\nb"))),
        )

        val line = pipe.written.single()
        assertFalse("no raw line break inside a message", line.contains('\n'))
        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals("2.0", parsed.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("a\nb", parsed.getValue("params").jsonObject.getValue("text").jsonPrimitive.content)
    }

    /** A notification (no id) goes to [JsonRpcConnection.notifications] and does not complete a pending call. */
    @Test
    fun notificationsRouteToFlowAndNotToPendingCalls() = runTest {
        val pipe = FakeLinePipe { line ->
            push("""{"jsonrpc":"2.0","method":"event","params":{"type":"turn","terminal":"t15s","seq":1,"data":{}}}""")
            push(withId(emptyResult(), requestIdOf(line)))
        }
        val connection = JsonRpcConnection(pipe, backgroundScope)
        val notifications = mutableListOf<RpcNotification>()
        backgroundScope.launch { connection.notifications.collect { notifications += it } }

        val result = connection.call("terminal.list", JsonObject(emptyMap()))
        runCurrent()

        assertEquals(emptyResult().getValue("result"), result)
        assertEquals(listOf("event"), notifications.map { it.method })
    }

    /** `error.data.kind` maps to the typed exception, and the hint is kept on it. */
    @Test
    fun errorKindsMapToTypedExceptions() = runTest {
        val notFound = assertThrowsSuspend(NotFound::class.java) {
            callReplying(errorReply(-32000, kind = "not_found", hint = "see `hc ls --all`"))
        }
        assertEquals("see `hc ls --all`", notFound.hint)

        val conflict = assertThrowsSuspend(Conflict::class.java) {
            callReplying(errorReply(-32000, kind = "conflict", hint = "retry"))
        }
        assertEquals("retry", conflict.hint)

        assertThrowsSuspend(InvalidRequest::class.java) {
            callReplying(errorReply(-32000, kind = "invalid_request", hint = null))
        }

        assertThrowsSuspend(InvalidRequest::class.java) {
            callReplying(errorReply(-32600, kind = null, hint = null))
        }

        val unknown = assertThrowsSuspend(Unknown::class.java) {
            callReplying(errorReply(-32000, kind = "busy", hint = "wait a moment"))
        }
        assertEquals("busy", unknown.kind)
        assertEquals(-32000, unknown.code)
        assertEquals("wait a moment", unknown.hint)
    }

    /** A line that is not a JSON object fails the connection, and the exception does not echo the line. */
    @Test
    fun malformedLineFailsTheConnectionWithoutEchoingIt() = runTest {
        val pipe = FakeLinePipe { _ -> push("this is terminal text, not json") }
        val connection = JsonRpcConnection(pipe, backgroundScope)

        val error = assertThrowsSuspend(TransportLost::class.java) {
            connection.call("terminal.read", JsonObject(emptyMap()))
        }

        assertFalse(error.message.orEmpty().contains("terminal text"))
    }

    /** `machine_list.json` has no client method: it is replayed through the connection and its values asserted. */
    @Test
    fun machineListFixtureReplaysThroughTheConnection() = runTest {
        val fixture = Fixtures.load("machine_list.json")
        val connection = JsonRpcConnection(FakeLinePipe.replaying(fixture), backgroundScope)

        val result = connection.call("machine.list", JsonObject(emptyMap()))
        val machines = ResponseJson.decodeFromJsonElement(MachineListWire.serializer(), result).machines

        val homelab: CoreMachine = machines.single().toDomain()
        assertEquals("homelab", homelab.name)
        assertEquals("dfccaaad4e6f3a1973f27b54498b887f", homelab.id)
        assertEquals("root@homelab", homelab.target)
        assertEquals("connected", homelab.state)
        assertEquals("0.1.14", homelab.version)
        assertFalse(homelab.via)
    }

    /** `events_seen.json` is notifications only: each is parsed in order, with no request. */
    @Test
    fun eventsSeenFixtureReplaysAsNotificationsOnly() = runTest {
        val pipe = FakeLinePipe()
        val connection = JsonRpcConnection(pipe, backgroundScope)
        val notifications = mutableListOf<RpcNotification>()
        backgroundScope.launch { connection.notifications.collect { notifications += it } }

        Fixtures.loadNotifications("events_seen.json").forEach {
            pipe.push(Json.encodeToString(JsonObject.serializer(), it))
        }
        runCurrent()

        val events = notifications.map { HcEvent.fromParams(it.params) }
        assertEquals(listOf(9505L, 9506L, 9509L), events.map { it.seq })
        assertTrue(events[0] is HcEvent.TerminalChanged)
        assertTrue(events[1] is HcEvent.LabelsChanged)
        assertTrue(events[2] is HcEvent.TerminalChanged)
        assertTrue(pipe.written.isEmpty())
    }

    /**
     * P0-7: a write that never completes fails the call at its window. Before, the write sat outside the timeout, so the call
     * (and the write lock) waited forever. The window is virtual time, so the test is exact.
     */
    @Test
    fun aWriteThatNeverCompletesFailsTheCallAtItsTimeout() = runTest {
        val connection = JsonRpcConnection(FakeLinePipe { awaitCancellation() }, backgroundScope)
        val started = testScheduler.currentTime
        var failure: HcException? = null

        try {
            connection.call("terminal.list", JsonObject(emptyMap()), timeoutMillis = 5_000L)
        } catch (e: HcException) {
            failure = e
        }

        assertTrue("the call failed with a timeout", failure is Timeout)
        assertEquals("it failed at its window", 5_000L, testScheduler.currentTime - started)
    }

    /**
     * P0-7: a stuck write on one connection does not stall another connection. Each connection has its own write lock and
     * reader, so the other call completes while the first one is still waiting.
     */
    @Test
    fun aStuckWriteOnOneConnectionDoesNotStallAnother() = runTest {
        val stuck = JsonRpcConnection(FakeLinePipe { awaitCancellation() }, backgroundScope)
        val answering = JsonRpcConnection(
            FakeLinePipe { line -> push(withId(emptyResult(), requestIdOf(line))) },
            backgroundScope,
        )
        val stuckCall = launch { runCatching { stuck.call("terminal.list", JsonObject(emptyMap()), timeoutMillis = 5_000L) } }
        runCurrent()

        answering.call("terminal.list", JsonObject(emptyMap()), timeoutMillis = 5_000L)

        assertTrue("the other connection answered while the stuck call was still waiting", stuckCall.isActive)
        stuckCall.join()
    }

    private fun emptyResult(): JsonObject =
        JsonObject(mapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "result" to JsonObject(emptyMap())))

    private fun errorReply(code: Int, kind: String?, hint: String?): JsonObject {
        val data = JsonObject(
            buildMap<String, JsonElement> {
                kind?.let { put("kind", JsonPrimitive(it)) }
                hint?.let { put("hint", JsonPrimitive(it)) }
            },
        )
        val error = JsonObject(
            mapOf<String, JsonElement>(
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive("failed"),
                "data" to data,
            ),
        )
        return JsonObject(mapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "error" to error))
    }

    /** Sends one call on a connection whose pipe answers with [reply] (id rewritten), and returns its result. */
    private suspend fun TestScope.callReplying(reply: JsonObject): JsonElement {
        val pipe = FakeLinePipe { line -> push(withId(reply, requestIdOf(line))) }
        return JsonRpcConnection(pipe, backgroundScope).call("terminal.read", JsonObject(emptyMap()))
    }
}
