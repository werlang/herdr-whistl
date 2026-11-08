package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.scriptedPipe
import io.github.werlang.whistl.support.withParam
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `terminal.send` with and without `force` (phase 4 r2). The unforced request is `send.json` itself; the forced request is
 * derived in memory from it, because no fixture records a forced send. The `blocked` error is derived the same way.
 */
class TerminalSendTest {

    /** Without `force` the request is exactly the recorded one: the field is omitted. */
    @Test
    fun anUnforcedSendIsTheRecordedRequest() = runTest {
        val sent = clientWith(command = FakeLinePipe.replaying(Fixtures.load("send.json")))
            .terminalSend("t15s", "echo hello-from-whistl", force = null)

        assertEquals(null, sent.status)
    }

    /** With `force: true` the request is the recorded one plus `force: true`, and nothing else changes. */
    @Test
    fun aForcedSendAddsOnlyTheForceField() = runTest {
        val recorded = Fixtures.load("send.json")
        val forced = recorded.request.withParam("force", JsonPrimitive(true))
        val pipe = scriptedPipe(Exchange("derived in memory from send.json: force true", forced, recorded.response))

        clientWith(command = pipe).terminalSend("t15s", "echo hello-from-whistl", force = true)

        assertEquals(1, pipe.written.size)
    }

    /** hc's `blocked` kind is a typed exception, with hc's hint kept. */
    @Test
    fun theBlockedKindIsTheTypedBlockedException() = runTest {
        val refusal = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32000),
                        "message" to JsonPrimitive("t15s is waiting for input"),
                        "data" to JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive("blocked"),
                                "hint" to JsonPrimitive("send with force: true to answer it"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val pipe = FakeLinePipe.replaying(Fixtures.load("send.json"), response = refusal)

        val error = assertThrowsSuspend(Blocked::class.java) {
            clientWith(command = pipe).terminalSend("t15s", "echo hello-from-whistl")
        }

        assertEquals("send with force: true to answer it", error.hint)
    }
}
