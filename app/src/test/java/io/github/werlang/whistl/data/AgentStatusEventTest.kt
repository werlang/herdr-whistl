package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.TerminalEventKind
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A `status` event whose status is hc's object form, `{"app":"claude-code","state":"working"}`, sets the status from its
 * `state` (phase 7 r5). The string form still works. Before the fix the object read as no status, so a Claude agent's
 * status changes were lost.
 */
class AgentStatusEventTest {

    private fun terminal(address: String): Terminal = Terminal(
        address = address, name = null, argv = emptyList(), cwd = null, labels = emptyMap(),
        state = TerminalState.Live, status = null, turn = 0, exitCode = null, createdMs = null, finishedMs = null,
        title = null, pid = null, signal = null, reason = null, keep = false,
    )

    private fun statusEvent(data: JsonObject): HcEvent.TerminalChanged =
        HcEvent.TerminalChanged(seq = 1L, terminal = "t1", kind = TerminalEventKind.Status, data = data)

    /** The object form sets the status from `state`. */
    @Test
    fun aStatusObjectSetsTheStatusFromItsState() {
        val data = buildJsonObject { put("status", buildJsonObject { put("app", "claude-code"); put("state", "working") }) }
        val after = AgentEventReducer.apply(mapOf("t1" to terminal("t1")), statusEvent(data))
        assertEquals(AgentStatus.Working, after.getValue("t1").status)
    }

    /** The plain string form still sets the status. */
    @Test
    fun aStatusStringSetsTheStatus() {
        val data = buildJsonObject { put("status", "done") }
        val after = AgentEventReducer.apply(mapOf("t1" to terminal("t1")), statusEvent(data))
        assertEquals(AgentStatus.Done, after.getValue("t1").status)
    }
}
