package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Clock
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.clientWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [InstalledAgentsDetector] throttling (SPEC §5, round p3r1 §2.8), on the virtual clock. Each `machine.exec` is one
 * request written to the fake pipe, so the pipe's write count is the exec count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAgentsDetectorTest {
    private val execFixture = Fixtures.load("exec_installed.json")

    @Test
    fun twoCallsInsideSixtySecondsProduceOneMachineExec() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val detector = InstalledAgentsDetector(Clock { testScheduler.currentTime })
        val client = clientWith(command = pipe)

        val first = detector.installed("m1", client)
        advanceTimeBy(30_000L)
        val second = detector.installed("m1", client)

        assertEquals(listOf("claude", "opencode"), first.map { it.id })
        assertEquals(first, second)
        assertEquals("machine.exec count", 1, pipe.written.size)
    }

    @Test
    fun aCallAtSixtyOneSecondsRunsAgain() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val detector = InstalledAgentsDetector(Clock { testScheduler.currentTime })
        val client = clientWith(command = pipe)

        detector.installed("m1", client)
        advanceTimeBy(61_000L)
        detector.installed("m1", client)

        assertEquals("machine.exec count", 2, pipe.written.size)
    }

    /** The window is at least 60 s: a call exactly at 60 s runs again, a call just under it does not. */
    @Test
    fun theWindowBoundaryIsSixtySeconds() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val detector = InstalledAgentsDetector(Clock { testScheduler.currentTime })
        val client = clientWith(command = pipe)

        detector.installed("m1", client)
        advanceTimeBy(59_999L)
        detector.installed("m1", client)
        assertEquals("just under 60 s is cached", 1, pipe.written.size)

        advanceTimeBy(1L)
        detector.installed("m1", client)
        assertEquals("exactly 60 s runs again", 2, pipe.written.size)
    }

    /** Each machine has its own window: a run on one does not throttle the other. */
    @Test
    fun twoMachinesThrottleIndependently() = runTest {
        val pipeA = FakeLinePipe.replaying(execFixture)
        val pipeB = FakeLinePipe.replaying(execFixture)
        val detector = InstalledAgentsDetector(Clock { testScheduler.currentTime })
        val clientA = clientWith(command = pipeA)
        val clientB = clientWith(command = pipeB)

        detector.installed("a", clientA)
        advanceTimeBy(30_000L)
        detector.installed("b", clientB)
        advanceTimeBy(31_000L)
        detector.installed("a", clientA)
        detector.installed("b", clientB)

        assertEquals("a: ran at 0 s and again at 61 s", 2, pipeA.written.size)
        assertEquals("b: ran at 30 s, cached at 61 s", 1, pipeB.written.size)
    }
}
