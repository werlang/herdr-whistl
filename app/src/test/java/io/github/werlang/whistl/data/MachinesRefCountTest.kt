package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.support.FakeMachineConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One live session per machine, reference-counted (round p3r1 §2.7). Counts are read from the fake connector after every
 * step. At no step may two sessions be open for one machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachinesRefCountTest {

    /** Asserts the single-session invariant: never more than one session open at once. */
    private fun assertAtMostOneOpen(h: MachinesRepositoryTest.Harness, step: String) {
        val open = h.connector.sessionsOpened - h.connector.sessionsClosed
        assertTrue("$step: opens - closes must stay at most 1, was $open", open <= 1)
    }

    /** Two acquires share one session: the connector is opened once and both leases see the client. */
    @Test
    fun twoAcquiresOpenTheSessionOnce() = runTest {
        val h = MachinesRepositoryTest.Harness(this)
        val id = h.addHomelab()

        val first = h.repo.acquire(id)
        runCurrent()
        val second = h.repo.acquire(id)
        runCurrent()

        assertEquals(1, h.connector.connectCalls)
        assertEquals(1, h.connector.sessionsOpened)
        assertEquals(0, h.connector.sessionsClosed)
        assertNotNull(first.client())
        assertNotNull(second.client())
    }

    /**
     * Releasing one lease keeps the session open; releasing the last closes it exactly once. A second release of the
     * same lease changes nothing.
     */
    @Test
    fun releaseOneKeepsOpenReleaseBothClosesOnce() = runTest {
        val h = MachinesRepositoryTest.Harness(this)
        val id = h.addHomelab()
        val first = h.repo.acquire(id)
        val second = h.repo.acquire(id)
        runCurrent()

        first.release()
        assertEquals(0, h.connector.sessionsClosed)
        assertNotNull(second.client())

        first.release()
        assertEquals(0, h.connector.sessionsClosed)

        second.release()
        assertEquals(1, h.connector.sessionsClosed)
        assertEquals(0, h.openSessions())
        assertEquals(null, second.client())
    }

    /**
     * A lost session and its reconnect: after each step, opens minus closes is at most one, and the run ends with exactly
     * one session open. The dead session is closed before the retry opens a new one.
     */
    @Test
    fun lostSessionIsClosedBeforeReconnectSoAtMostOneIsOpenAtEveryStep() = runTest {
        val h = MachinesRepositoryTest.Harness(this)
        val id = h.addHomelab()
        val lease = h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.connector.sessionsOpened)
        assertAtMostOneOpen(h, "after first open")

        h.connector.lastSession!!.kill(TransportLost("connection lost"))
        runCurrent()
        assertEquals(1, h.connector.sessionsClosed)
        assertEquals(0, h.openSessions())
        assertAtMostOneOpen(h, "after the loss, before the retry")

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, h.connector.sessionsOpened)
        assertAtMostOneOpen(h, "after the reconnect")
        assertEquals(1, h.openSessions())
        assertNotNull(lease.client())
    }

    /** Releasing the only lease during a retry wait stops the retry: no session appears afterwards. */
    @Test
    fun releasingDuringBackoffStopsTheRetry() = runTest {
        val h = MachinesRepositoryTest.Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Unreachable)
        val lease = h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)

        lease.release()
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(1, h.connector.connectCalls)
        assertEquals(0, h.connector.sessionsOpened)
    }

    /** A second machine never shares the first machine's session. */
    @Test
    fun machinesHaveSeparateSessions() = runTest {
        val h = MachinesRepositoryTest.Harness(this)
        val a = h.addHomelab()
        val b = h.addHomelab()

        h.repo.acquire(a)
        h.repo.acquire(b)
        runCurrent()

        assertEquals(2, h.connector.connectCalls)
        assertEquals(2, h.connector.sessionsOpened)
        assertEquals(2, h.openSessions())
    }
}
