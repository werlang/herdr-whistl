package io.github.werlang.whistl.demo

import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug demo data, which demo mode shows: every status on the connected machine, an offline machine with cached
 * spaces, an agent with no space, and a blocked count. Debug unit tests only; the release build has none of this.
 */
class FakeCoreTest {
    private val core = FakeCore()

    /** The debug provider returns the fake, so demo mode has data to show. */
    @Test
    fun theDebugProviderReturnsTheFake() {
        assertNotNull(DemoCores.fake())
    }

    /** Every status appears on the connected machine, and a plain program (no status) too. */
    @Test
    fun everyStatusAppearsOnTheConnectedMachine() {
        val statuses = core.agents.agents.value
            .filter { it.machineId == "mac" }
            .map { it.agent.terminal.status }
            .toSet()

        assertEquals(setOf<AgentStatus?>(null) + AgentStatus.entries, statuses)
    }

    /** An offline machine is listed, flagged offline, and shows its cached spaces; it has no agents. */
    @Test
    fun anOfflineMachineShowsItsCachedSpaces() {
        val groups = core.spaces.groups().value

        val lab = groups.single { it.machineId == "lab" }
        assertTrue(lab.offline)
        assertTrue(lab.spaces.isNotEmpty())
        assertFalse(groups.single { it.machineId == "mac" }.offline)
        assertTrue(core.agents.agents.value.none { it.machineId == "lab" })
    }

    /** At least one agent matches no space: the "No space" case. */
    @Test
    fun anAgentHasNoSpace() {
        assertTrue(core.agents.agents.value.any { it.space == null })
    }

    /** The blocked count is at least one, so the badge shows. */
    @Test
    fun blockedCountIsAtLeastOne() {
        assertTrue(core.agents.blockedCount.value >= 1)
    }

    /**
     * The badge equals the live blocked agents, by the shared rule. The demo also holds a finished agent whose status is
     * blocked, so the test shows the badge does not count it (round p3r3).
     */
    @Test
    fun blockedCountEqualsTheLiveBlockedAgents() {
        val agents = core.agents.agents.value
        val liveBlocked = agents.count { it.agent.terminal.isWaitingForUser() }

        assertEquals(liveBlocked, core.agents.blockedCount.value)
        assertEquals(1, liveBlocked)
        assertTrue(agents.any { it.agent.terminal.status == AgentStatus.Blocked && it.agent.terminal.state == TerminalState.Finished })
    }

    /** Closing a space kills its live agents and removes it; the agents of other spaces stay. */
    @Test
    fun closingASpaceRemovesItsLiveAgents() = runTest {
        assertEquals(SpaceResult.Done("sp_api"), core.spaces.close("mac", "sp_api"))

        val addresses = core.agents.agents.value.map { it.agent.terminal.address }
        assertFalse(addresses.contains("t-working"))
        assertFalse(addresses.contains("t-done"))
        assertTrue(addresses.contains("t-idle"))
        assertTrue(core.spaces.groups().value.single { it.machineId == "mac" }.spaces.none { it.id == "sp_api" })
    }

    /** A write to the offline machine is refused, and nothing changes there. */
    @Test
    fun writesAreRefusedOnAnOfflineMachine() = runTest {
        assertEquals(SpaceResult.MachineOffline, core.spaces.create("lab"))
    }
}
