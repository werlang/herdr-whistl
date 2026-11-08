package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Clock
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.testHello
import io.github.werlang.whistl.domain.Machine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The installed-agents state (phase 4b r1 §2.1): a machine's list is published when it connects, the detector's 60 s throttle
 * holds across a quick reconnect, and a removed machine leaves the map. The machines are scripted; the detection runs on the
 * real [InstalledAgentsDetector] over a replayed `machine.exec` answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAgentsRepositoryTest {

    private val execFixture = Fixtures.load("exec_installed.json")

    /** A machine with the given id. */
    private fun machine(id: String): Machine = Machine(id = id, name = id, host = "$id.lan", port = 22, user = "ci")

    /** Connecting a machine publishes the agents detected on it. */
    @Test
    fun connectingPublishesTheInstalledAgents() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        machines.leaseClient = clientWith(command = pipe)
        val repo = DefaultInstalledAgentsRepository(
            scope = backgroundScope,
            machines = machines,
            detector = InstalledAgentsDetector(Clock { testScheduler.currentTime }),
        )
        runCurrent()

        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()

        assertEquals(listOf("claude", "opencode"), repo.installed.value.getValue("m1").map { it.id })
        assertEquals("one machine.exec", 1, pipe.written.size)
    }

    /** A quick reconnect inside the detector's 60 s throttle asks the machine nothing new. */
    @Test
    fun aQuickReconnectInsideSixtySecondsRunsNoNewDetection() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        machines.leaseClient = clientWith(command = pipe)
        val repo = DefaultInstalledAgentsRepository(
            scope = backgroundScope,
            machines = machines,
            detector = InstalledAgentsDetector(Clock { testScheduler.currentTime }),
        )
        runCurrent()
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()

        machines.setState("m1", ConnectionState.Offline)
        runCurrent()
        advanceTimeBy(30_000L)
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()

        assertEquals("the second connection reuses the throttled result", 1, pipe.written.size)
        assertEquals(listOf("claude", "opencode"), repo.installed.value.getValue("m1").map { it.id })
    }

    /** Removing a machine leaves its list out of the map. */
    @Test
    fun removingTheMachineClearsItsList() = runTest {
        val pipe = FakeLinePipe.replaying(execFixture)
        val machines = ScriptedMachines()
        machines.put(machine("m1"), ConnectionState.Offline)
        machines.leaseClient = clientWith(command = pipe)
        val repo = DefaultInstalledAgentsRepository(
            scope = backgroundScope,
            machines = machines,
            detector = InstalledAgentsDetector(Clock { testScheduler.currentTime }),
        )
        runCurrent()
        machines.setState("m1", ConnectionState.Connected(testHello()))
        runCurrent()
        assertEquals(true, repo.installed.value.containsKey("m1"))

        machines.remove("m1")
        runCurrent()

        assertFalse(repo.installed.value.containsKey("m1"))
    }
}
