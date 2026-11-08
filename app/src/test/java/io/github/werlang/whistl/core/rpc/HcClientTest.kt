package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.clientWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Specific [HcClient] behaviour that the conformance table does not express: generation checks, typed conflicts,
 * the events subscription and its watchdog, closed pipes, call timeouts, and the fixed `terminal.run` flags.
 *
 * Time is virtual throughout: `runTest` advances it, so the 40 s watchdog is exercised without waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HcClientTest {

    /** `hello` at generation 2 is refused. The generation is patched in memory; the fixture on disk is unchanged. */
    @Test
    fun helloRejectsGenerationOtherThanOneWhenPatchedInMemory() = runTest {
        val fixture = Fixtures.load("hello.json")
        val result = fixture.response.getValue("result").jsonObject
        val patched = JsonObject(
            fixture.response + ("result" to JsonObject(result + ("generation" to JsonPrimitive(2)))),
        )
        val client = clientWith(command = FakeLinePipe.replaying(fixture, response = patched))

        val error = assertThrowsSuspend(UnsupportedGeneration::class.java) { client.hello() }

        assertEquals(2, error.generation)
    }

    /** `doc_set_conflict.json` surfaces as [Conflict], and hc's hint and message are kept. */
    @Test
    fun docSetConflictIsTypedWithHintAndMessageKept() = runTest {
        val client = clientWith(command = FakeLinePipe.replaying(Fixtures.load("doc_set_conflict.json")))

        val error = assertThrowsSuspend(Conflict::class.java) {
            client.docSet("whistl-fixture", Json.parseToJsonElement("""{"v":1,"spaces":[]}"""), version = 0L)
        }

        assertEquals("read doc.get and retry with its version", error.hint)
        assertTrue(error.message.orEmpty().contains("is at version 1"))
    }

    /**
     * The events snapshot from `subscribe.json` is followed by the three `events_seen.json` notifications, in order.
     * Applied through [TerminalReducer], each one changes the observable state.
     */
    @Test
    fun eventsSnapshotThenNotificationsInOrderMutateObservableState() = runTest {
        val events = FakeLinePipe.replaying(Fixtures.load("subscribe.json"))
        val sub = clientWith(events = events).eventsSubscribe(mapOf("whistl.fixture" to "1"))
        val collector = recordEvents(sub.events)
        val initial = sub.terminals.associateBy { it.address }

        assertEquals(9504L, sub.seq)
        assertEquals("sp-demo", initial.getValue("t15s").labels["whistl.space"])

        Fixtures.loadNotifications("events_seen.json").forEach { events.push(encode(it)) }
        runCurrent()

        assertEquals(3, collector.events.size)
        val finished = collector.events[0] as HcEvent.TerminalChanged
        assertEquals(TerminalEventKind.Finished, finished.kind)
        assertEquals(9505L, finished.seq)
        val labels = collector.events[1] as HcEvent.LabelsChanged
        assertEquals("sp-other", labels.labels["whistl.space"])
        assertEquals(9506L, labels.seq)
        val removed = collector.events[2] as HcEvent.TerminalChanged
        assertEquals(TerminalEventKind.Removed, removed.kind)
        assertEquals(9509L, removed.seq)

        val afterFinished = TerminalReducer.apply(initial, collector.events[0])
        assertEquals(TerminalState.Finished, afterFinished.getValue("t15s").state)
        val afterLabels = TerminalReducer.apply(afterFinished, collector.events[1])
        assertEquals("sp-other", afterLabels.getValue("t15s").labels["whistl.space"])
        val afterRemoved = TerminalReducer.apply(afterLabels, collector.events[2])
        assertTrue(afterRemoved.isEmpty())
        assertNull(collector.failure)
    }

    /** An event type this client does not know parses to [HcEvent.Unknown] and does not fail the stream. */
    @Test
    fun unknownEventTypeDoesNotFailTheStream() = runTest {
        val events = FakeLinePipe.replaying(Fixtures.load("subscribe.json"))
        val sub = clientWith(events = events).eventsSubscribe(mapOf("whistl.fixture" to "1"))
        val collector = recordEvents(sub.events)

        events.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"future_kind","terminal":"t15s","seq":9510,"data":{}}}""")
        runCurrent()

        val unknown = collector.events.single() as HcEvent.Unknown
        assertEquals("future_kind", unknown.type)
        assertEquals("t15s", unknown.terminal)
        assertNull(collector.failure)
    }

    /** 39 s of silence after the last line does not fail the stream (the control case). */
    @Test
    fun thirtyNineSecondsOfSilenceDoesNotFailTheStream() = runTest {
        val sub = subscribe()
        val collector = recordEvents(sub.events)

        advanceTimeBy(39_000)
        runCurrent()

        assertNull(collector.failure)
    }

    /** 40 s of silence after the last line fails the stream with [TransportLost]. */
    @Test
    fun fortySecondsOfSilenceFailsTheStreamWithTransportLost() = runTest {
        val sub = subscribe()
        val collector = recordEvents(sub.events)

        advanceTimeBy(40_000)
        runCurrent()

        assertTrue(collector.failure is TransportLost)
    }

    /** A heartbeat restarts the 40 s window: silence measured from the heartbeat, not from subscribe. */
    @Test
    fun heartbeatRestartsTheWatchdogWindow() = runTest {
        val events = FakeLinePipe.replaying(Fixtures.load("subscribe.json"))
        val sub = clientWith(events = events).eventsSubscribe(mapOf("whistl.fixture" to "1"))
        val collector = recordEvents(sub.events)

        advanceTimeBy(30_000)
        events.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"heartbeat","terminal":null,"seq":9510,"data":{}}}""")
        runCurrent()
        advanceTimeBy(39_000)
        runCurrent()
        assertNull("39 s after the heartbeat must not fail", collector.failure)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(collector.failure is TransportLost)
    }

    /** The pipe closing while a call waits fails that call with [TransportLost]. */
    @Test
    fun closedPipeMidCallFailsWithTransportLost() = runTest {
        val pipe = FakeLinePipe()
        val client = clientWith(command = pipe)

        supervisorScope {
            val call = async { client.terminalList() }
            runCurrent()
            pipe.end()

            assertThrowsSuspend(TransportLost::class.java) { call.await() }
        }
    }

    /** A call with no reply fails with [Timeout] once its timeout passes, on virtual time. */
    @Test
    fun perCallTimeoutIsTimeoutUnderVirtualTime() = runTest {
        val connection = JsonRpcConnection(FakeLinePipe(), backgroundScope)

        supervisorScope {
            val call = async {
                connection.call("doc.get", JsonObject(mapOf("name" to JsonPrimitive("x"))), timeoutMillis = 5_000)
            }

            advanceTimeBy(4_999)
            runCurrent()
            assertFalse("not timed out at 4999 ms", call.isCompleted)

            advanceTimeBy(1)
            runCurrent()
            val error = assertThrowsSuspend(Timeout::class.java) { call.await() }
            assertTrue(error.message.orEmpty().contains("doc.get"))
        }
    }

    /** `terminal.run` always writes `no_parent: true` and `no_notify: true`, and omits `keep` when unset. */
    @Test
    fun terminalRunAlwaysSendsNoParentAndNoNotify() = runTest {
        val pipe = FakeLinePipe.replaying(Fixtures.load("run.json"))

        clientWith(command = pipe).terminalRun(
            argv = listOf("sh", "-c", "echo whistl-fixture ready; sleep 60"),
            cwd = "/tmp",
            labels = mapOf("whistl.fixture" to "1", "whistl.space" to "sp-demo"),
            name = "whistl-fixture",
        )

        val params = Json.parseToJsonElement(pipe.written.single()).jsonObject.getValue("params").jsonObject
        assertEquals(JsonPrimitive(true), params["no_parent"])
        assertEquals(JsonPrimitive(true), params["no_notify"])
        assertFalse("keep is omitted when unset", params.containsKey("keep"))
    }

    /** Events from a subscription, for the watchdog tests. */
    private suspend fun TestScope.subscribe(): EventSubscription =
        clientWith(events = FakeLinePipe.replaying(Fixtures.load("subscribe.json")))
            .eventsSubscribe(mapOf("whistl.fixture" to "1"))

    /** Collects a stream in the background, recording its events and any failure. */
    private class Collector {
        val events: MutableList<HcEvent> = mutableListOf()
        var failure: Throwable? = null
    }

    private fun TestScope.recordEvents(stream: Flow<HcEvent>): Collector {
        val collector = Collector()
        backgroundScope.launch {
            try {
                stream.collect { collector.events += it }
            } catch (e: TransportLost) {
                collector.failure = e
            }
        }
        return collector
    }

    private fun encode(notification: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), notification)
}
