package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.KnownAgents
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.Exchange
import io.github.werlang.whistl.support.FakeLinePipe
import io.github.werlang.whistl.support.Fixtures
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.clientWith
import io.github.werlang.whistl.support.scriptedPipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `AgentsRepository.start` (phase 4b r2, SPEC §2.3, §5): the argv, cwd and labels each start sends, the login shell read once
 * per connection, and the typed results. The request shape is the one in `docs/protocol-fixtures/run.json`, derived in memory;
 * the spawn failure is the recorded `run_spawn_failed.json` reply. The machine is scripted offline, so only the start's own
 * commands reach the pipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentStartTest {

    private val runFixture = Fixtures.load("run.json")
    private val spawnFailedFixture = Fixtures.load("run_spawn_failed.json")
    private val claude = KnownAgents.table.first { it.id == "claude" }

    /**
     * A `terminal.run` request for [argv] in [cwd], labelled with [space]. Its keys are those of the request in `run.json`.
     *
     * @param argv the program and its arguments.
     * @param cwd the working directory.
     * @param space the space label.
     * @return the request.
     */
    private fun runRequest(argv: List<String>, cwd: String = WEB, space: String = SPACE): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 0)
        put("method", "terminal.run")
        putJsonObject("params") {
            putJsonArray("argv") { argv.forEach { add(JsonPrimitive(it)) } }
            put("cwd", cwd)
            putJsonObject("labels") { put("whistl.space", space) }
            put("no_parent", true)
            put("no_notify", true)
        }
    }

    /** The `machine.exec {argv:["printenv","SHELL"]}` request (SPEC §5). */
    private fun shellRequest(): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 0)
        put("method", "machine.exec")
        putJsonObject("params") {
            putJsonArray("argv") {
                add(JsonPrimitive("printenv"))
                add(JsonPrimitive("SHELL"))
            }
        }
    }

    /**
     * A successful `machine.exec` reply whose stdout is [stdout]. hc sends stdout as base64, so the reply does too.
     *
     * @param stdout the decoded stdout.
     * @return the reply.
     */
    private fun shellReply(stdout: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 0)
        putJsonObject("result") {
            put("code", 0)
            put("stdout", Base64.getEncoder().encodeToString(stdout.toByteArray()))
            put("stderr", Base64.getEncoder().encodeToString(ByteArray(0)))
            put("timed_out", false)
            put("truncated", false)
        }
    }

    /**
     * A repository whose one machine is offline (so no event subscription uses the pipe), and whose start commands go to
     * [pipe] through the lease's client.
     *
     * @param scope the test scope; the repository runs on its background scope.
     * @param pipe the command channel the start uses.
     * @param resolution the space's folder resolution, or `null` for a folder not checked yet.
     * @return the repository and the scripted machines (to change the connection).
     */
    private fun repo(
        scope: TestScope,
        pipe: FakeLinePipe,
        resolution: FolderResolution? = FolderResolution.Resolved(WEB),
    ): Pair<DefaultAgentsRepository, ScriptedMachines> {
        val machines = ScriptedMachines()
        machines.put(Machine(id = MACHINE, name = MACHINE, host = "$MACHINE.lan", port = 22, user = "ci"), ConnectionState.Offline)
        machines.leaseClient = scope.clientWith(command = pipe)
        val resolutions = if (resolution == null) emptyMap() else mapOf(SPACE to resolution)
        val spaces = StaticSpaces(
            listOf(
                MachineSpaces(
                    machineId = MACHINE,
                    offline = false,
                    spaces = listOf(SpaceRecord(id = SPACE, name = "web", folder = "~/web", pinned = false, order = 0)),
                    resolutions = resolutions,
                ),
            ),
        )
        val agents = DefaultAgentsRepository(
            scope = scope.backgroundScope,
            machines = machines,
            spaces = spaces,
            documentChanges = DocumentChanges(),
        )
        return agents to machines
    }

    /** The derived run request has the keys of run.json's request, without its optional name. */
    @Test
    fun theRunRequestHasTheShapeOfRunJson() = runTest {
        val fixtureKeys = runFixture.request.getValue("params").jsonObject.keys - "name"
        val derivedKeys = runRequest(listOf("claude")).getValue("params").jsonObject.keys
        assertEquals("terminal.run", runFixture.request.getValue("method").jsonPrimitive.content)
        assertEquals("keys of run.json's params, without the optional name", fixtureKeys, derivedKeys)
    }

    /** A known agent runs its argv as it is, in the space's resolved folder, labelled with the space. */
    @Test
    fun aKnownAgentRunsItsArgvInTheSpaceFolderUnderTheSpaceLabel() = runTest {
        val pipe = scriptedPipe(Exchange("run claude", runRequest(claude.argv), runFixture.response))
        val (agents, _) = repo(this, pipe)

        val result = agents.start(MACHINE, SPACE, StartChoice.Known(claude))

        assertEquals(StartResult.Started("t15s"), result)
        assertEquals("one terminal.run and nothing else", 1, pipe.written.size)
    }

    /** The terminal reads the machine's SHELL, and runs it as a login shell. */
    @Test
    fun theTerminalRunsTheMachinesShellAsALoginShell() = runTest {
        val pipe = scriptedPipe(
            Exchange("printenv SHELL", shellRequest(), shellReply("/bin/zsh\n")),
            Exchange("run zsh -l", runRequest(listOf("/bin/zsh", "-l")), runFixture.response),
        )
        val (agents, _) = repo(this, pipe)

        assertEquals(StartResult.Started("t15s"), agents.start(MACHINE, SPACE, StartChoice.Terminal))
    }

    /** An empty SHELL falls back to `/bin/sh -l`. */
    @Test
    fun anEmptyShellFallsBackToBinSh() = runTest {
        val pipe = scriptedPipe(
            Exchange("printenv SHELL", shellRequest(), shellReply("")),
            Exchange("run sh -l", runRequest(listOf("/bin/sh", "-l")), runFixture.response),
        )
        val (agents, _) = repo(this, pipe)

        assertEquals(StartResult.Started("t15s"), agents.start(MACHINE, SPACE, StartChoice.Terminal))
    }

    /** A SHELL that is not an absolute path falls back to `/bin/sh -l` too. */
    @Test
    fun aRelativeShellFallsBackToBinSh() = runTest {
        val pipe = scriptedPipe(
            Exchange("printenv SHELL", shellRequest(), shellReply("zsh\n")),
            Exchange("run sh -l", runRequest(listOf("/bin/sh", "-l")), runFixture.response),
        )
        val (agents, _) = repo(this, pipe)

        assertEquals(StartResult.Started("t15s"), agents.start(MACHINE, SPACE, StartChoice.Terminal))
    }

    /** SHELL is read once per connection: a second terminal on the same connection does not read it again. */
    @Test
    fun theShellIsReadOncePerConnection() = runTest {
        val pipe = scriptedPipe(
            Exchange("printenv SHELL", shellRequest(), shellReply("/bin/zsh\n")),
            Exchange("run zsh -l (1)", runRequest(listOf("/bin/zsh", "-l")), runFixture.response),
            Exchange("run zsh -l (2)", runRequest(listOf("/bin/zsh", "-l")), runFixture.response),
        )
        val (agents, _) = repo(this, pipe)

        agents.start(MACHINE, SPACE, StartChoice.Terminal)
        agents.start(MACHINE, SPACE, StartChoice.Terminal)

        assertEquals("one printenv and two runs", 3, pipe.written.size)
    }

    /** A new connection reads the shell again: the answer belongs to the connection it was read on. */
    @Test
    fun aNewConnectionReadsTheShellAgain() = runTest {
        val first = scriptedPipe(
            Exchange("printenv SHELL (first)", shellRequest(), shellReply("/bin/zsh\n")),
            Exchange("run zsh -l", runRequest(listOf("/bin/zsh", "-l")), runFixture.response),
        )
        val second = scriptedPipe(
            Exchange("printenv SHELL (second)", shellRequest(), shellReply("/bin/bash\n")),
            Exchange("run bash -l", runRequest(listOf("/bin/bash", "-l")), runFixture.response),
        )
        val (agents, machines) = repo(this, first)
        agents.start(MACHINE, SPACE, StartChoice.Terminal)

        machines.leaseClient = clientWith(command = second)
        val result = agents.start(MACHINE, SPACE, StartChoice.Terminal)

        assertEquals(StartResult.Started("t15s"), result)
        assertEquals("the second connection was asked for its shell and ran once", 2, second.written.size)
    }

    /** An unresolved folder never reaches terminal.run: no request leaves, and the reason is typed. */
    @Test
    fun anUnresolvableFolderSendsNoRunAndGivesItsReason() = runTest {
        val pipe = scriptedPipe()
        val (agents, _) = repo(this, pipe, FolderResolution.Unresolvable(UnresolvableReason.NotFound))

        val result = agents.start(MACHINE, SPACE, StartChoice.Known(claude))

        assertEquals(StartResult.FolderUnavailable(UnresolvableReason.NotFound), result)
        assertTrue("no command was sent", pipe.written.isEmpty())
    }

    /** A folder not checked yet on this device is unavailable with no reason, and sends nothing. */
    @Test
    fun aFolderNotCheckedYetSendsNoRun() = runTest {
        val pipe = scriptedPipe()
        val (agents, _) = repo(this, pipe, resolution = null)

        assertEquals(StartResult.FolderUnavailable(null), agents.start(MACHINE, SPACE, StartChoice.Known(claude)))
        assertTrue("no command was sent", pipe.written.isEmpty())
    }

    /** The machine's refusal to spawn the program (hc `spawn_failed`) is a typed could-not-start. */
    @Test
    fun aSpawnFailureIsCouldNotStart() = runTest {
        val pipe = scriptedPipe(Exchange("run claude (spawn failed)", runRequest(claude.argv), spawnFailedFixture.response))
        val (agents, _) = repo(this, pipe)

        assertEquals(StartResult.CouldNotStart, agents.start(MACHINE, SPACE, StartChoice.Known(claude)))
    }

    /** A space the machine does not hold is reported as unknown, and nothing is sent. */
    @Test
    fun anUnknownSpaceSendsNothing() = runTest {
        val pipe = scriptedPipe()
        val (agents, _) = repo(this, pipe)

        assertEquals(StartResult.UnknownSpace, agents.start(MACHINE, "sp_gone", StartChoice.Terminal))
        assertTrue(pipe.written.isEmpty())
    }

    private companion object {
        const val MACHINE = "m1"
        const val SPACE = "sp_web"
        const val WEB = "/home/ci/web"
    }
}
