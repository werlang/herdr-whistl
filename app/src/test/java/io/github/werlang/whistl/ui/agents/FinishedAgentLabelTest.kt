package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.R
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.support.FakePins
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventsSnapshot
import io.github.werlang.whistl.support.snapshotPipe
import io.github.werlang.whistl.support.terminalJson
import io.github.werlang.whistl.ui.agentLabelRes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * V3 (phase 4b r1): a finished terminal is labelled `finished`, whatever its last status was, and only a live terminal that is
 * blocked reads `needs you`. The label is the one the Agents list and the Agent screen both show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishedAgentLabelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** A finished agent whose last status was blocked is labelled finished, not needs you. */
    @Test
    fun aFinishedBlockedAgentIsLabelledFinished() {
        assertEquals(
            R.string.status_finished,
            agentLabelRes(live = false, waiting = false, status = AgentStatus.Blocked),
        )
    }

    /** Needs you is only for a live terminal that waits for the user. */
    @Test
    fun needsYouOnlyForALiveTerminalWaitingForTheUser() {
        assertEquals(
            R.string.status_needs_you,
            agentLabelRes(live = true, waiting = true, status = AgentStatus.Blocked),
        )
        assertEquals(
            R.string.status_working,
            agentLabelRes(live = true, waiting = false, status = AgentStatus.Working),
        )
    }

    /** The Agents list row of a finished blocked agent is not live and not waiting, so its label is finished. */
    @Test
    fun theAgentsRowOfAFinishedBlockedAgentIsNotLive() = runTest {
        val h = RepoStack(this)
        h.connector.eventsPipeFactory = {
            snapshotPipe(eventsSnapshot(1, listOf(terminalJson("t-finished", state = "finished", status = "blocked"))))
        }
        h.addMachine()
        runCurrent()
        val vm = AgentsViewModel(h.agents, h.machines, FakePins())
        runCurrent()

        val row = vm.state.value.rows.single()

        assertFalse(row.live)
        assertFalse(row.waiting)
        assertEquals(R.string.status_finished, agentLabelRes(row.live, row.waiting, row.status))
    }
}
