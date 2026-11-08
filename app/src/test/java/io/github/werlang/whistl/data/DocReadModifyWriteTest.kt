package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.Conflict
import io.github.werlang.whistl.core.rpc.NotFound
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.assertThrowsSuspend
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.errorReply
import io.github.werlang.whistl.support.scriptedPipe
import io.github.werlang.whistl.support.versionReply
import io.github.werlang.whistl.support.withParam
import io.github.werlang.whistl.support.withResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `docReadModifyWrite` through the real [io.github.werlang.whistl.core.rpc.HcClient] over a scripted pipe (SPEC §5).
 *
 * The sequence in the first test is the one the brief fixes: `doc_get_missing.json` → `doc_set_conflict.json` (a
 * conflict) → `doc_get.json` (version 1) → a `doc.set` at version 1 that succeeds. No fixture records that last
 * `doc.set`. It is derived **in memory** from `doc_set.json` (version patched to 1, reply version 2) and is labelled
 * "derived in memory" in each test. No file is written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocReadModifyWriteTest {
    private val name = "whistl-fixture"
    private val missing = Fixtures.load("doc_get_missing.json")
    private val conflict = Fixtures.load("doc_set_conflict.json")
    private val read = Fixtures.load("doc_get.json")
    private val set = Fixtures.load("doc_set.json")
    private val unknownTerminal = Fixtures.load("unknown_terminal.json")

    /** Missing → conflict → re-read (version 1) → the retried write at version 1 succeeds. */
    @Test
    fun conflictThenRereadThenWriteSucceeds() = runTest {
        val pipe = scriptedPipe(
            Exchange("doc_get_missing.json", missing.request, missing.response),
            Exchange("doc_set_conflict.json", conflict.request, conflict.response),
            Exchange("doc_get.json", read.request, read.response),
            Exchange(
                "derived in memory from doc_set.json: version 1, reply version 2",
                set.request.withParam("version", JsonPrimitive(1)),
                versionReply(2),
            ),
        )

        val written = docReadModifyWrite(clientWith(command = pipe), name, maxAttempts = 3) { it }

        assertEquals(SpacesDocument.empty(), written)
        assertEquals(4, pipe.written.size)
    }

    /**
     * Unknown fields injected in memory into the `doc_get.json` value come back unchanged in the retried `doc.set`.
     * The injected value is derived in memory; no file is written.
     */
    @Test
    fun unknownFieldsInTheReadValueAppearUnchangedInTheRetriedWrite() = runTest {
        val injected = Json.parseToJsonElement(
            """
            {"v":1,
             "spaces":[{"id":"sp_0123456789abcdef","name":"web-app","folder":"~/code/web-app",
                        "pinned":true,"order":0,"future_field":{"n":1}}],
             "future_top":"keep-me"}
            """.trimIndent(),
        ).jsonObject
        val readAgain = read.response.withResult(
            JsonObject(mapOf<String, JsonElement>("value" to injected, "version" to JsonPrimitive(1))),
        )
        val pipe = scriptedPipe(
            Exchange("doc_get_missing.json", missing.request, missing.response),
            Exchange("doc_set_conflict.json", conflict.request, conflict.response),
            Exchange("derived in memory from doc_get.json: value with unknown fields, version 1", read.request, readAgain),
            Exchange(
                "derived in memory from doc_set.json: injected value, version 1, reply version 2",
                set.request.withParam("value", injected).withParam("version", JsonPrimitive(1)),
                versionReply(2),
            ),
        )

        val written = docReadModifyWrite(clientWith(command = pipe), name, maxAttempts = 3) { it }

        assertEquals(injected, written.toJson())
        assertEquals(4, pipe.written.size)
    }

    /** With `maxAttempts = 2`, a second conflict is rethrown as [Conflict], with hc's hint, and nothing more is sent. */
    @Test
    fun exhaustingMaxAttemptsRethrowsConflict() = runTest {
        val newerConflict = JsonObject(
            conflict.response + (
                "error" to JsonObject(
                    conflict.response.getValue("error").jsonObject +
                        ("message" to JsonPrimitive("document \"whistl-fixture\" is at version 2, not 1")),
                )
                ),
        )
        val pipe = scriptedPipe(
            Exchange("doc_get_missing.json", missing.request, missing.response),
            Exchange("doc_set_conflict.json", conflict.request, conflict.response),
            Exchange("doc_get.json", read.request, read.response),
            Exchange(
                "derived in memory from doc_set.json: version 1, conflict again",
                set.request.withParam("version", JsonPrimitive(1)),
                newerConflict,
            ),
        )

        val error = assertThrowsSuspend(Conflict::class.java) {
            docReadModifyWrite(clientWith(command = pipe), name, maxAttempts = 2) { it }
        }

        assertEquals("read doc.get and retry with its version", error.hint)
        assertEquals(4, pipe.written.size)
    }

    /**
     * A `not_found` error is not a conflict: it propagates on its first occurrence and no retry is sent.
     * The error reply is the `error` object from `unknown_terminal.json`, derived in memory for `doc.set`.
     */
    @Test
    fun notFoundIsNotRetried() = runTest {
        val notFoundError = errorReply(
            JsonObject(
                unknownTerminal.response.getValue("error").jsonObject +
                    ("message" to JsonPrimitive("document \"whistl-fixture\" does not exist")),
            ),
        )
        val pipe = scriptedPipe(
            Exchange("doc_get_missing.json", missing.request, missing.response),
            Exchange(
                "derived in memory: doc_set_conflict.json request, not_found error from unknown_terminal.json",
                conflict.request,
                notFoundError,
            ),
        )

        val error = assertThrowsSuspend(NotFound::class.java) {
            docReadModifyWrite(clientWith(command = pipe), name, maxAttempts = 3) { it }
        }

        assertEquals("see `hc ls --all`", error.hint)
        assertEquals(2, pipe.written.size)
    }
}
