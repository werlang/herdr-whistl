package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Event decoding is total (phase 6 P0-2): a line whose params do not decode is dropped and counted; the events after it still
 * arrive, in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HcEventDecodingTest {

    /** The decoder answers null for every shape that is not an event object, and never throws. */
    @Test
    fun aParamsShapeThatIsNotAnEventDecodesToNull() {
        val shapes = listOf("null", "[1,2]", "\"text\"", "{\"type\":\"created\",\"terminal\":\"t\",\"data\":[]}", "{\"terminal\":1}")
        shapes.forEach { raw ->
            assertNull("params $raw", HcEvent.fromParamsOrNull(kotlinx.serialization.json.Json.parseToJsonElement(raw)))
        }
    }

    /** Malformed lines on a live subscription are dropped and counted, and the valid event after them is delivered. */
    @Test
    fun malformedLinesAreCountedAndTheValidEventStillArrives() = runTest {
        val events: FakeLinePipe = snapshotPipe(eventsSnapshot(1, emptyList()))
        val client = HcClient(
            command = JsonRpcConnection(FakeLinePipe(), backgroundScope),
            openEventsPipe = { events },
            scope = backgroundScope,
        )
        val subscription = client.eventsSubscribe(null)
        val received = mutableListOf<HcEvent>()
        backgroundScope.launch { subscription.events.collect { received += it } }

        events.push("""{"jsonrpc":"2.0","method":"event","params":null}""")
        events.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"created","terminal":"t-x","data":["array"]}}""")
        events.push("""{"jsonrpc":"2.0","method":"event","params":"text"}""")
        events.push(eventLine("created", "t-ok", 2, terminalJson("t-ok")))
        runCurrent()

        assertEquals("three lines were dropped and counted", 3L, subscription.droppedLines)
        assertEquals(listOf("t-ok"), received.filterIsInstance<HcEvent.TerminalChanged>().map { it.terminal })
    }
}
