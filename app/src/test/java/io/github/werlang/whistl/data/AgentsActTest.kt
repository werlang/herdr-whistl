package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.scriptedPipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AgentsRepository.act` (phase 9, SPEC §2.4): the four actions the Agents list offers, and what each sends to the core.
 *
 * The requests are the recorded ones in `docs/protocol-fixtures/` — `interrupt.json`, `kill.json`, `rm.json`,
 * `label.json` — so a change in the wire shape fails here rather than on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsActTest {
    private val interrupt = Fixtures.load("interrupt.json")
    private val kill = Fixtures.load("kill.json")
    private val rm = Fixtures.load("rm.json")
    private val label = Fixtures.load("label.json")

    private fun request(method: String, address: String, labels: Map<String, String>? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", method)
            putJsonObject("params") {
                put("terminal", JsonPrimitive(address))
                if (labels != null) {
                    putJsonObject("labels") { labels.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                }
            }
        }

    private fun repo(scope: TestScope, pipe: io.github.werlang.whistl.support.FakeLinePipe): DefaultAgentsRepository {
        val machines = ScriptedMachines()
        machines.put(Machine(id = MACHINE, name = MACHINE, host = "$MACHINE.lan", port = 22, user = "ci"), ConnectionState.Offline)
        machines.leaseClient = scope.clientWith(command = pipe)
        return DefaultAgentsRepository(
            scope = scope.backgroundScope,
            machines = machines,
            spaces = StaticSpaces(emptyList()),
            documentChanges = DocumentChanges(),
        )
    }

    /** Interrupt sends `terminal.interrupt` for that address and nothing else. */
    @Test
    fun interruptSendsTheInterruptForThatTerminal() = runTest {
        val pipe = scriptedPipe(Exchange("interrupt", interrupt.request, interrupt.response))
        val agents = repo(this, pipe)

        // The recorded interrupt is for t161, so the test acts on that address: the exchange compares both ways.
        assertEquals(ActionResult.Done, agents.act(MACHINE, INTERRUPTED, AgentAction.Interrupt))
        assertEquals(1, pipe.written.size)
        val sent = Json.parseToJsonElement(pipe.written.single()).jsonObject
        assertEquals("terminal.interrupt", sent["method"]?.jsonPrimitive?.content)
        assertEquals(INTERRUPTED, sent["params"]?.jsonObject?.get("terminal")?.jsonPrimitive?.content)
    }

    /** Stop kills the process and keeps the terminal, so the row stays with its finished state. */
    @Test
    fun stopKillsAndNothingElse() = runTest {
        val pipe = scriptedPipe(Exchange("kill", kill.request, kill.response))
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Done, agents.act(MACHINE, ADDRESS, AgentAction.Stop))
        assertEquals(1, pipe.written.size)
        assertEquals("terminal.kill", Json.parseToJsonElement(pipe.written.single()).jsonObject["method"]?.jsonPrimitive?.content)
    }

    /** Close kills and then removes: the row leaves the list because the terminal is gone. */
    @Test
    fun closeKillsAndRemoves() = runTest {
        val pipe = scriptedPipe(
            Exchange("kill", kill.request, kill.response),
            Exchange("rm", rm.request, rm.response),
        )
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Done, agents.act(MACHINE, ADDRESS, AgentAction.Close))
        assertEquals(
            listOf("terminal.kill", "terminal.rm"),
            pipe.written.map { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content },
        )
    }

    /** A terminal that finished already still gets removed: the kill's NotFound is not an error on the way to close. */
    @Test
    fun closingAFinishedTerminalStillRemovesIt() = runTest {
        val notFound = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(3),
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32000),
                        "message" to JsonPrimitive("no such terminal"),
                        "data" to JsonObject(mapOf("kind" to JsonPrimitive("not_found"))),
                    ),
                ),
            ),
        )
        val pipe = scriptedPipe(
            Exchange("kill", kill.request, notFound),
            Exchange("rm", rm.request, rm.response),
        )
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Done, agents.act(MACHINE, ADDRESS, AgentAction.Close))
    }

    /** A remove that races the reaping is retried: `StillLive` from hc is not the end of a close. */
    @Test
    fun closingRetriesTheRemoveWhileTheCoreStillCountsItLive() = runTest {
        val stillLive = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(3),
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32000),
                        "message" to JsonPrimitive("terminal is still live"),
                        "data" to JsonObject(mapOf("kind" to JsonPrimitive("live"))),
                    ),
                ),
            ),
        )
        val pipe = scriptedPipe(
            Exchange("kill", kill.request, kill.response),
            Exchange("rm still live", rm.request, stillLive),
            Exchange("rm again", rm.request, rm.response),
        )
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Done, agents.act(MACHINE, ADDRESS, AgentAction.Close))
        assertEquals(3, pipe.written.size)
    }

    /** Rename writes the `whistl.name` label and nothing else, trimmed. */
    @Test
    fun renameWritesTheNameLabel() = runTest {
        val pipe = scriptedPipe(Exchange("label", request("terminal.label", ADDRESS, mapOf("whistl.name" to "deploy")), label.response))
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Done, agents.act(MACHINE, ADDRESS, AgentAction.Rename("  deploy  ")))
        val sent = Json.parseToJsonElement(pipe.written.single()).jsonObject["params"]?.jsonObject?.get("labels")?.jsonObject
        assertEquals("deploy", sent?.get("whistl.name")?.jsonPrimitive?.content)
    }

    /** An empty name never reaches the core: it is a refusal the list can report. */
    @Test
    fun anEmptyRenameIsRefused() = runTest {
        val pipe = scriptedPipe()
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Failed(AgentAction.Rename("   ")), agents.act(MACHINE, ADDRESS, AgentAction.Rename("   ")))
        assertEquals("nothing was sent", 0, pipe.written.size)
    }

    /** A missing terminal is reported as gone, so the row can be allowed to disappear. */
    @Test
    fun aMissingTerminalIsGone() = runTest {
        val notFound = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(3),
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(-32000),
                        "message" to JsonPrimitive("no such terminal"),
                        "data" to JsonObject(mapOf("kind" to JsonPrimitive("not_found"))),
                    ),
                ),
            ),
        )
        val pipe = scriptedPipe(Exchange("interrupt", interrupt.request, notFound))
        val agents = repo(this, pipe)

        assertEquals(ActionResult.Gone, agents.act(MACHINE, INTERRUPTED, AgentAction.Interrupt))
    }

    private companion object {
        const val MACHINE = "rpi5"
        const val ADDRESS = "t15s"

        /** The address in the recorded `interrupt.json`. */
        const val INTERRUPTED = "t161"
    }
}
