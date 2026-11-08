package io.github.werlang.whistl.data

import io.github.werlang.whistl.support.RepoStack
import io.github.werlang.whistl.support.eventLine
import io.github.werlang.whistl.support.terminalJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A malformed `event` notification from the core (phase 6 P0-2): `params` null, `data` an array, `params` a string. Each is
 * dropped, the subscription stays alive, and the next valid event still reaches the agents list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MalformedEventTest {

    /** Three malformed event lines, then a valid `created`: the agent appears and the subscription is still alive. */
    @Test
    fun malformedEventLinesDoNotEndTheSubscriptionAndTheNextValidEventAppears() = runTest {
        val stack = RepoStack(this)
        stack.addMachine()
        runCurrent()

        stack.pushEvent("""{"jsonrpc":"2.0","method":"event","params":null}""")
        stack.pushEvent("""{"jsonrpc":"2.0","method":"event","params":{"type":"created","terminal":"t-bad","data":["array"]}}""")
        stack.pushEvent("""{"jsonrpc":"2.0","method":"event","params":"just text"}""")
        stack.pushEvent(eventLine("created", "t-ok", 2, terminalJson("t-ok")))
        runCurrent()

        val addresses = stack.agents.agents.value.map { it.agent.terminal.address }
        assertEquals("the valid agent appeared and the malformed ones did not", listOf("t-ok"), addresses)
    }
}
