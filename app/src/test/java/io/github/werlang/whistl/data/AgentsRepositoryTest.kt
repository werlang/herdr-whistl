package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.FakeMachineConnector
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.dataOf
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.spaceJson
import io.github.werlang.whistl.support.spacesDocJson
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultAgentsRepository] on virtual time (round p3r2). Each event test pushes one notification and asserts the agent
 * list after it. Tests named *Assumed* use a shape that no recording covers; the shape is stated in the test (see
 * `event-shapes.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsRepositoryTest {

    /** A stack with one connected machine seeded with [terminals]. */
    private suspend fun TestScope.connected(terminals: List<JsonObject> = emptyList()): Pair<RepoStack, String> {
        val h = RepoStack(this)
        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, terminals)) }
        val id = h.addMachine()
        runCurrent()
        return h to id
    }

    private fun RepoStack.addresses(): List<String> = agents.agents.value.map { it.agent.terminal.address }

    private fun RepoStack.only() = agents.agents.value.single().agent.terminal

    /** observed: `created` adds the full terminal object under its address. */
    @Test
    fun observedCreatedAddsTheFullTerminal() = runTest {
        val (h, _) = connected()

        h.pushEvent(
            eventLine(
                "created", "t-new", 2,
                terminalJson("t-new", labels = mapOf("whistl.space" to "sp_a"), cwd = "/tmp", title = "fresh"),
            ),
        )
        runCurrent()

        val terminal = h.only()
        assertEquals("t-new", terminal.address)
        assertEquals(TerminalState.Live, terminal.state)
        assertNull(terminal.status)
        assertEquals("sp_a", terminal.labels["whistl.space"])
        assertEquals("/tmp", terminal.cwd)
    }

    /** observed: `labels` replaces the whole label map. */
    @Test
    fun observedLabelsReplacesTheLabelMap() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", labels = mapOf("whistl.space" to "sp_a"))))

        h.pushEvent(eventLine("labels", "t1", 2, dataOf("labels" to JsonObject(mapOf("whistl.space" to JsonPrimitive("sp_b"), "whistl.capture" to JsonPrimitive("1"))))))
        runCurrent()

        assertEquals(mapOf("whistl.space" to "sp_b", "whistl.capture" to "1"), h.only().labels)
    }

    /** observed: `keep` changes the keep flag. */
    @Test
    fun observedKeepChangesTheKeepFlag() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", keep = false)))

        h.pushEvent(eventLine("keep", "t1", 2, dataOf("keep" to JsonPrimitive(true))))
        runCurrent()

        assertTrue(h.only().keep)
    }

    /** observed: `title` changes the title. */
    @Test
    fun observedTitleChangesTheTitle() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))

        h.pushEvent(eventLine("title", "t1", 2, dataOf("title" to JsonPrimitive("whistl-title"))))
        runCurrent()

        assertEquals("whistl-title", h.only().title)
    }

    /** observed, not in SPEC §5: `foreground` parses as unknown and changes nothing, and does not crash. */
    @Test
    fun observedForegroundIsIgnoredAndChangesNothing() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", status = "working")))
        val before = h.agents.agents.value

        h.pushEvent(
            eventLine(
                "foreground", "t1", 2,
                dataOf(
                    "argv" to JsonArray(listOf(JsonPrimitive("bash"))),
                    "cwd" to JsonPrimitive("/private/tmp"),
                    "name" to JsonPrimitive("bash"),
                    "pid" to JsonPrimitive(16854),
                ),
            ),
        )
        runCurrent()

        assertEquals(before, h.agents.agents.value)
        assertEquals(AgentStatus.Working, h.only().status)
    }

    /** observed: `finished` sets the state, exit code, finish time, reason and signal, and keeps the agent listed. */
    @Test
    fun observedFinishedSetsStateAndExitAndKeepsTheAgentListed() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))

        h.pushEvent(
            eventLine(
                "finished", "t1", 2,
                dataOf(
                    "exit_code" to JsonNull,
                    "finished_ms" to JsonPrimitive(1794114200934L),
                    "reason" to JsonPrimitive("killed"),
                    "signal" to JsonPrimitive(1),
                ),
            ),
        )
        runCurrent()

        val terminal = h.only()
        assertEquals(TerminalState.Finished, terminal.state)
        assertNull(terminal.exitCode)
        assertEquals(1794114200934L, terminal.finishedMs)
        assertEquals("killed", terminal.reason)
        assertEquals(1, terminal.signal)
    }

    /** observed: `removed` removes the agent from the list. */
    @Test
    fun observedRemovedRemovesTheAgent() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1"), terminalJson("t2")))

        h.pushEvent(eventLine("removed", "t1", 2, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(listOf("t2"), h.addresses())
    }

    /**
     * assumed shape `{"status": "blocked", "status_ms": …}`: `status` changes the status, and [blockedCount] follows it
     * (the Agents badge).
     */
    @Test
    fun assumedStatusChangesStatusAndFollowsBlockedCount() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", status = "working")))
        assertEquals(0, h.agents.blockedCount.value)

        h.pushEvent(eventLine("status", "t1", 2, dataOf("status" to JsonPrimitive("blocked"), "status_ms" to JsonPrimitive(1L))))
        runCurrent()
        assertEquals(AgentStatus.Blocked, h.only().status)
        assertEquals(1, h.agents.blockedCount.value)

        h.pushEvent(eventLine("status", "t1", 3, dataOf("status" to JsonPrimitive("idle"))))
        runCurrent()
        assertEquals(AgentStatus.Idle, h.only().status)
        assertEquals(0, h.agents.blockedCount.value)
    }

    /** assumed shape `{"turn": 3}`: `turn` changes the turn counter. */
    @Test
    fun assumedTurnChangesTheTurnCounter() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))

        h.pushEvent(eventLine("turn", "t1", 2, dataOf("turn" to JsonPrimitive(3))))
        runCurrent()

        assertEquals(3L, h.only().turn)
    }

    /** assumed shape `{"cols": 120, "rows": 40}`: `size` changes nothing the app models. */
    @Test
    fun assumedSizeChangesNothing() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        val before = h.agents.agents.value

        h.pushEvent(eventLine("size", "t1", 2, dataOf("cols" to JsonPrimitive(120), "rows" to JsonPrimitive(40))))
        runCurrent()

        assertEquals(before, h.agents.agents.value)
    }

    /** assumed shape `{}`: `lost` finishes the terminal with reason `lost` (the documented choice) and keeps it listed. */
    @Test
    fun assumedLostFinishesTheTerminalWithReasonLost() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))

        h.pushEvent(eventLine("lost", "t1", 2, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(TerminalState.Finished, h.only().state)
        assertEquals("lost", h.only().reason)
    }

    /** assumed shape `{}`: `restarted` brings a finished terminal back to live and clears its exit. */
    @Test
    fun assumedRestartedRevivesAFinishedTerminal() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", state = "finished")))

        h.pushEvent(eventLine("restarted", "t1", 2, JsonObject(emptyMap())))
        runCurrent()

        val terminal = h.only()
        assertEquals(TerminalState.Live, terminal.state)
        assertNull(terminal.exitCode)
        assertNull(terminal.finishedMs)
    }

    /**
     * assumed shape `{"name": "whistl", "version": 2}` with no terminal: a `doc` event for `whistl` announces the change
     * to the spaces repository, and the agent list does not change.
     */
    @Test
    fun assumedDocAnnouncesTheDocumentChangeAndChangesNoAgent() = runTest {
        val (h, id) = connected(listOf(terminalJson("t1")))
        val before = h.agents.agents.value
        val announced = mutableListOf<String>()
        backgroundScope.launch { h.docChanges.changes.collect { announced += it } }
        runCurrent()

        h.pushEvent(eventLine("doc", null, 2, dataOf("name" to JsonPrimitive("whistl"), "version" to JsonPrimitive(2))))
        runCurrent()

        assertEquals(listOf(id), announced)
        assertEquals(before, h.agents.agents.value)
    }

    /** assumed shape `{}`: a `heartbeat` changes no state. */
    @Test
    fun assumedHeartbeatChangesNoState() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        val before = h.agents.agents.value

        h.pushEvent(eventLine("heartbeat", null, 2, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(before, h.agents.agents.value)
    }

    /**
     * assumed shape `{"terminals": [...]}`: a `missed` event replaces the machine's agents with the snapshot it carries.
     */
    @Test
    fun assumedMissedReplacesTheMachinesAgentsWithTheSnapshot() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1"), terminalJson("t2")))

        h.pushEvent(
            eventLine(
                "missed", null, 2,
                dataOf("terminals" to JsonArray(listOf(terminalJson("t3", status = "done")))),
            ),
        )
        runCurrent()

        assertEquals(listOf("t3"), h.addresses())
        assertEquals(AgentStatus.Done, h.only().status)
    }

    /** An event type this client does not know is ignored and does not crash. */
    @Test
    fun unknownEventTypeIsIgnored() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        val before = h.agents.agents.value

        h.pushEvent(eventLine("from_a_newer_hc", "t1", 2, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(before, h.agents.agents.value)
    }

    /** An event for an address that is not in the list changes nothing and does not crash. */
    @Test
    fun eventForAnUnknownAddressDoesNotCrash() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        val before = h.agents.agents.value

        h.pushEvent(eventLine("status", "t-missing", 2, dataOf("status" to JsonPrimitive("blocked"))))
        h.pushEvent(eventLine("finished", "t-missing", 3, dataOf("reason" to JsonPrimitive("exited"))))
        runCurrent()

        assertEquals(before, h.agents.agents.value)
    }

    /** The events channel is opened once per session: at most one is open at any step, through a reconnect. */
    @Test
    fun atMostOneEventsSubscriptionIsOpenAtEveryStep() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        fun open() = h.connector.eventsPipes.count { !it.isClosed }

        assertEquals(1, h.connector.eventsPipes.size)
        assertEquals(1, open())

        h.connector.lastSession!!.kill()
        runCurrent()
        assertEquals("old session's channel closed before the retry", 0, open())

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, h.connector.eventsPipes.size)
        assertEquals(1, open())
    }

    /** A reconnect seeds the machine's agents again from the new snapshot. */
    @Test
    fun reconnectReseedsTheAgentsFromTheNewSnapshot() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1")))
        assertEquals(listOf("t1"), h.addresses())

        h.connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(5, listOf(terminalJson("t9")))) }
        h.connector.lastSession!!.kill()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("t9"), h.addresses())
    }

    /** A heartbeat every 15 s keeps the session alive for 60 s: the watchdog never reports a loss. */
    @Test
    fun heartbeatsKeepTheSessionAliveBeyondFortySeconds() = runTest {
        val (h, id) = connected(listOf(terminalJson("t1")))

        repeat(4) { step ->
            advanceTimeBy(15_000L)
            h.pushEvent(eventLine("heartbeat", null, 10L + step, JsonObject(emptyMap())))
            runCurrent()
        }

        assertEquals(0, h.connector.lastSession!!.reportedLosses)
        assertTrue(h.machines.observe(id).value is ConnectionState.Connected)
        assertEquals(listOf("t1"), h.addresses())
    }

    /**
     * 39 s of silence after the last line is not a loss; 40 s of silence reports the loss through the session, the
     * machine goes offline, and its agents leave the list.
     */
    @Test
    fun fortySecondsOfSilenceReportsTheLossAndRemovesTheAgents() = runTest {
        val (h, id) = connected(listOf(terminalJson("t1")))

        advanceTimeBy(39_000L)
        runCurrent()
        assertEquals(0, h.connector.lastSession!!.reportedLosses)

        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(h.connector.lastSession!!.reportedLosses >= 1)
        assertEquals(ConnectionState.Offline, h.machines.observe(id).value)
        assertTrue(h.agents.agents.value.isEmpty())
    }

    /** Each agent carries its machine and its matched space: label, then cwd, then "No space". */
    @Test
    fun agentsCarryTheirMatchedSpace() = runTest {
        val h = RepoStack(this)
        h.core.value = spacesDocJson(
            listOf(
                spaceJson("sp_a", "web", "/home/u/web"),
                spaceJson("sp_b", "api", "/home/u/api/"),
            ),
        )
        h.connector.eventsPipeFactory = {
            snapshotPipe(
                eventsSnapshot(
                    1,
                    listOf(
                        terminalJson("t-label", labels = mapOf("whistl.space" to "sp_b"), cwd = "/home/u/web"),
                        terminalJson("t-cwd", cwd = "/home/u/api"),
                        terminalJson("t-none", cwd = "/tmp"),
                    ),
                ),
            )
        }
        h.addMachine()
        runCurrent()

        val matched = h.agents.agents.value.associate { it.agent.terminal.address to it.spaceId }
        assertEquals(mapOf("t-label" to "sp_b", "t-cwd" to "sp_b", "t-none" to null), matched)
    }

    /** Removing a machine removes its agents and releases its lease. */
    @Test
    fun removingAMachineRemovesItsAgents() = runTest {
        val (h, id) = connected(listOf(terminalJson("t1")))
        assertEquals(1, h.agents.agents.value.size)

        h.machines.remove(id)
        runCurrent()

        assertTrue(h.agents.agents.value.isEmpty())
        assertEquals(0, h.connector.sessionsOpened - h.connector.sessionsClosed)
    }

    /** Until a machine is connected, no agents are shown and the badge stays hidden. */
    @Test
    fun noAgentsAreShownWhileNoMachineIsConnected() = runTest {
        val h = RepoStack(this)
        h.connector.script(FakeMachineConnector.Attempt.Unreachable)
        h.addMachine()
        runCurrent()

        assertTrue(h.agents.agents.value.isEmpty())
        assertEquals(0, h.agents.blockedCount.value)
    }

    /**
     * observed `finished` on a blocked agent: it stays listed with the status it kept, but the badge drops it, because a
     * finished program cannot be waiting for the user (round p3r3).
     */
    @Test
    fun blockedThenFinishedLeavesTheBadge() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", status = "blocked")))
        assertEquals(1, h.agents.blockedCount.value)

        h.pushEvent(eventLine("finished", "t1", 2, dataOf("exit_code" to JsonNull, "reason" to JsonPrimitive("exited"))))
        runCurrent()

        assertEquals(TerminalState.Finished, h.only().state)
        assertEquals(AgentStatus.Blocked, h.only().status)
        assertEquals(0, h.agents.blockedCount.value)
    }

    /**
     * assumed shape `{}`: `restarted` makes the terminal live again. The reducer keeps the status it had, which is
     * `blocked`, so the terminal is live and blocked again and the badge counts it again (round p3r3). Expectation: 1.
     */
    @Test
    fun assumedRestartedBringsTheBlockedAgentBackToTheBadge() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", status = "blocked")))
        h.pushEvent(eventLine("finished", "t1", 2, dataOf("exit_code" to JsonNull)))
        runCurrent()
        assertEquals(0, h.agents.blockedCount.value)

        h.pushEvent(eventLine("restarted", "t1", 3, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(TerminalState.Live, h.only().state)
        assertEquals(AgentStatus.Blocked, h.only().status)
        assertEquals(1, h.agents.blockedCount.value)
    }

    /**
     * assumed shape `{}`: `lost` finishes a blocked agent (reason `lost`). It is no longer waiting, so the badge drops it,
     * and the agent stays listed (round p3r3).
     */
    @Test
    fun assumedLostOnABlockedAgentIsNotCounted() = runTest {
        val (h, _) = connected(listOf(terminalJson("t1", status = "blocked")))
        assertEquals(1, h.agents.blockedCount.value)

        h.pushEvent(eventLine("lost", "t1", 2, JsonObject(emptyMap())))
        runCurrent()

        assertEquals(TerminalState.Finished, h.only().state)
        assertEquals(0, h.agents.blockedCount.value)
        assertEquals(listOf("t1"), h.addresses())
    }

    /**
     * A finished blocked terminal in the snapshot is listed but not counted, and beside a live blocked one only the live
     * one counts (round p3r3).
     */
    @Test
    fun onlyTheLiveBlockedAgentIsCountedBesideAFinishedBlockedOne() = runTest {
        val (h, _) = connected(
            listOf(
                terminalJson("t1", status = "blocked"),
                terminalJson("t2", state = "finished", status = "blocked"),
            ),
        )

        assertEquals(2, h.agents.agents.value.size)
        assertEquals(1, h.agents.blockedCount.value)
    }
}
