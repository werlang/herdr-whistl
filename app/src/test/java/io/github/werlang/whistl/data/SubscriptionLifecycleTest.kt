package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.DEFAULT_CALL_TIMEOUT_MS
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.JsonRpcConnection
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.testHello
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The events subscription's lifecycle (phase 6 P0-6): at every step of subscribe, fail, resubscribe, lost, resubscribe there is at
 * most one events connection open, and each one that ends is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionLifecycleTest {

    /** Every events connection the client has opened, in order. */
    private val opened = mutableListOf<FakeLinePipe>()

    /** The number of events connections not yet closed. */
    private fun live(): Int = opened.count { !it.isClosed }

    /**
     * A client whose events connections come from [plan], in order. Each opened connection is recorded.
     *
     * @param plan the connections to hand out, in order of opening.
     * @return the client.
     */
    private fun TestScope.client(plan: ArrayDeque<FakeLinePipe>): HcClient = HcClient(
        command = JsonRpcConnection(FakeLinePipe(), backgroundScope),
        openEventsPipe = { plan.removeFirst().also { opened += it } },
        scope = backgroundScope,
    )

    /** A connection that never answers `events.subscribe`. */
    private fun silent(): FakeLinePipe = FakeLinePipe()

    /** A connection that answers `events.subscribe` with an empty snapshot. */
    private fun answering(): FakeLinePipe = snapshotPipe(eventsSnapshot(1, emptyList()))

    @Test
    fun atMostOneEventsConnectionIsOpenAtEveryStep() = runTest {
        val plan = ArrayDeque(listOf(silent(), answering(), answering(), answering()))
        val machines = ScriptedMachines()
        machines.put(Machine(id = "m1", name = "m1", host = "m1.lan", port = 22, user = "ci"), ConnectionState.Offline)
        machines.leaseClient = client(plan)
        DefaultAgentsRepository(backgroundScope, machines, StaticSpaces(emptyList()), DocumentChanges())
        runCurrent()

        // 1. Connected, and the subscribe never answers: it times out, and its connection is closed.
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()
        assertEquals("one connection opened for the subscribe", 1, opened.size)
        assertTrue("at most one open", live() <= 1)
        advanceTimeBy(DEFAULT_CALL_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("the timed-out subscribe closed its connection", 0, live())

        // 2. Resubscribe: the new subscribe answers, and exactly one connection is open.
        machines.setState("m1", ConnectionState.Offline)
        runCurrent()
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()
        assertEquals("the resubscribe opened a second connection", 2, opened.size)
        assertEquals("exactly one open after the resubscribe", 1, live())

        // 3. The live subscription is replaced by leaving Connected: its connection is closed before anything else opens one.
        machines.setState("m1", ConnectionState.Offline)
        runCurrent()
        assertEquals("cancelling the live subscription closed its connection", 0, live())
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()
        assertEquals("the next subscription opened a third connection", 3, opened.size)
        assertEquals("exactly one open again", 1, live())

        // 4. The events channel ends: the subscription fails, and its connection is closed.
        opened[2].end()
        runCurrent()
        assertEquals("the lost subscription closed its connection", 0, live())

        // 5. Resubscribe after the loss: one open again.
        machines.setState("m1", ConnectionState.Offline)
        runCurrent()
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()
        assertEquals("fourth connection opened", 4, opened.size)
        assertEquals("exactly one open after the second resubscribe", 1, live())
    }
}
