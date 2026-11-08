package io.github.werlang.whistl.data

import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.FakeCoreDoc
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event shapes through the real repositories (phase 6 P0-5): the recorded `heartbeat` and `doc` events, and the synthetic `missed`
 * in both its shapes. Each line is the recorded or synthetic fixture's notification, sent over the events channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventShapesRepositoryTest {

    /** A connected machine whose events snapshot holds [events] and whose core holds the whistl document. */
    private suspend fun TestScope.connected(events: List<JsonObject>): RepoStack {
        val h = RepoStack(this)
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, events)) }
        h.addMachine()
        runCurrent()
        return h
    }

    /** The notification object of a single-notification fixture, as the wire line the events channel sends. */
    private fun line(name: String): String = Json.encodeToString(
        JsonObject.serializer(),
        Json.parseToJsonElement(Fixtures.text(name)).jsonObject.getValue("notification").jsonObject,
    )

    /** The notification object at [index] of the synthetic fixture, as its wire line. */
    private fun syntheticLine(index: Int): String = Json.encodeToString(
        JsonObject.serializer(),
        Json.parseToJsonElement(Fixtures.text("event_missed_synthetic.json")).jsonObject
            .getValue("notifications").jsonArray[index].jsonObject,
    )

    private fun FakeCoreDoc.docReads(): Int = methods.count { it == "doc.get" }

    /**
     * The recorded doc event names `whistl.fixture`, not `whistl`, and still re-reads the whistl document: any doc event is a
     * change worth a read (phase 6 P0-5).
     */
    @Test
    fun aRecordedDocEventRereadsTheWhistlDocument() = runTest {
        val h = connected(emptyList())
        val before = h.core.docReads()

        h.pushEvent(line("event_doc.json"))
        runCurrent()

        assertEquals(before + 1, h.core.docReads())
    }

    /** The recorded heartbeat is a line and changes no agent: the agents on the machine stay as they were. */
    @Test
    fun aRecordedHeartbeatChangesNoAgent() = runTest {
        val h = connected(listOf(terminalJson("t-a")))
        val before = h.agents.agents.value.map { it.agent.terminal.address }

        h.pushEvent(line("event_heartbeat.json"))
        runCurrent()

        assertEquals(before, h.agents.agents.value.map { it.agent.terminal.address })
        assertEquals(listOf("t-a"), before)
    }

    /**
     * The synthetic `missed` in its top-level shape replaces the machine's agent list with the snapshot it carries. The fixture's
     * snapshot holds no terminals, so the listed agent leaves.
     */
    @Test
    fun missedSyntheticTopLevelShapeReplacesTheAgentListWithItsSnapshot() = runTest {
        val h = connected(listOf(terminalJson("t-a")))
        assertEquals(listOf("t-a"), h.agents.agents.value.map { it.agent.terminal.address })

        h.pushEvent(syntheticLine(0))
        runCurrent()

        assertTrue("the snapshot replaced the list", h.agents.agents.value.isEmpty())
    }

    /** The synthetic `missed` in its `event` shape replaces the agent list the same way. */
    @Test
    fun missedSyntheticEventShapeReplacesTheAgentListWithItsSnapshot() = runTest {
        val h = connected(listOf(terminalJson("t-a")))

        h.pushEvent(syntheticLine(1))
        runCurrent()

        assertTrue("the snapshot replaced the list", h.agents.agents.value.isEmpty())
    }

    /** A `missed` without a `terminals` array carries no snapshot, so it changes nothing: the agents stay as they were. */
    @Test
    fun aMissedWithoutATerminalsArrayChangesNothing() = runTest {
        val h = connected(listOf(terminalJson("t-a")))

        h.pushEvent("""{"jsonrpc":"2.0","method":"missed","params":{"seq":9}}""")
        runCurrent()

        assertEquals(listOf("t-a"), h.agents.agents.value.map { it.agent.terminal.address })
    }

    /**
     * Heartbeats arrive as lines of their own and keep the events channel alive: 60 s of them, each within the 40 s watchdog, and the
     * machine stays connected with its agent.
     */
    @Test
    fun recordedHeartbeatsKeepTheEventsChannelAliveBeyondTheWatchdog() = runTest {
        val h = connected(listOf(terminalJson("t-a")))

        repeat(4) {
            advanceTimeBy(15_000L)
            h.pushEvent(line("event_heartbeat.json"))
            runCurrent()
        }

        assertEquals(0, h.connector.lastSession!!.reportedLosses)
        assertEquals(listOf("t-a"), h.agents.agents.value.map { it.agent.terminal.address })
    }
}
