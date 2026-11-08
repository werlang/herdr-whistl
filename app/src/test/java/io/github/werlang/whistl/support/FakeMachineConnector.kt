package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.JsonRpcConnection
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.core.ssh.HostKeyRefused
import io.github.werlang.whistl.core.ssh.MachineConnector
import io.github.werlang.whistl.core.ssh.MachineSession
import io.github.werlang.whistl.core.ssh.HostKeyGate
import io.github.werlang.whistl.core.ssh.SshAuth
import io.github.werlang.whistl.core.ssh.SshWire
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Machine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.werlang.whistl.core.rpc.LinePipe

/**
 * A [MachineConnector] for lifecycle tests. Each connection runs the real host-key gate and a real `hello` through
 * [HcClient] over a [FakeLinePipe] that replays `hello.json`, so generation checks and key decisions are the production
 * ones. Only the transport is fake.
 *
 * Each `connect` call takes the next scripted [Attempt]; without a script every attempt is [Attempt.Healthy].
 * Counters record the calls and the sessions opened and closed.
 *
 * @param scope the scope for the fake command connection's reader; tests pass `backgroundScope`.
 */
class FakeMachineConnector(private val scope: CoroutineScope) : MachineConnector {
    /** How one scripted connection attempt goes. */
    sealed interface Attempt {
        /** The default key is presented, trusted, and `hello` succeeds. */
        data object Healthy : Attempt

        /** The login is refused: [AuthFailed]. */
        data object AuthRefused : Attempt

        /** The connection cannot be opened: [TransportLost]. */
        data object Unreachable : Attempt

        /** `hello` reports generation 2: [UnsupportedGeneration]. */
        data object GenerationTwo : Attempt

        /**
         * The connection fails with [error] exactly as thrown. Lets a test present the failure the way the production
         * transport does (for example a `TransportLost` wrapping an sshj `TransportException`).
         *
         * @property error the failure to throw.
         */
        class Fails(val error: Throwable) : Attempt

        /**
         * A different host key is presented. For an unpinned machine this is an unknown key; for a pinned one, a changed key.
         *
         * @property blob the presented key blob.
         */
        class PresentKey(val blob: ByteArray) : Attempt
    }

    private val queued = ArrayDeque<Attempt>()

    /** Calls to `connect`, including failed attempts. */
    var connectCalls: Int = 0
        private set

    /** Attempts that reached `hello`. */
    var helloCalls: Int = 0
        private set

    /** Sessions handed out. */
    var sessionsOpened: Int = 0
        private set

    /** Sessions closed. A session closes at most once. */
    var sessionsClosed: Int = 0
        private set

    /** The most recent session handed out, for simulating its loss. */
    var lastSession: FakeSession? = null
        private set

    /** The Ed25519 key blob that healthy attempts present. */
    val defaultKey: ByteArray = SshWire.ed25519Blob(ByteArray(32) { 1 })

    /**
     * Builds the events channel each `eventsSubscribe` opens. The default is an empty pipe, as in the lifecycle tests.
     * Agent tests replace it with a pipe that answers `events.subscribe` with a snapshot.
     */
    var eventsPipeFactory: () -> FakeLinePipe = { FakeLinePipe() }

    /** Every events channel opened on any session, in order. */
    val eventsPipes: MutableList<FakeLinePipe> = mutableListOf()

    /**
     * Answers command-channel requests other than `hello`. The request is passed without its `id`; the reply is sent
     * with the client's id. Returning `null` stays silent, so the request times out.
     */
    var commandReply: (JsonObject) -> JsonObject? = { null }

    /**
     * Queues attempts, consumed in order by later `connect` calls.
     *
     * @param attempts the attempts to queue.
     */
    fun script(vararg attempts: Attempt) {
        queued.addAll(attempts.toList())
    }

    override suspend fun connect(machine: Machine, auth: SshAuth, gate: HostKeyGate): MachineSession {
        connectCalls++
        val attempt = queued.removeFirstOrNull() ?: Attempt.Healthy
        if (attempt is Attempt.Unreachable) throw TransportLost("open failed")
        if (attempt is Attempt.Fails) throw attempt.error
        if (attempt is Attempt.AuthRefused) throw AuthFailed()
        val presented = (attempt as? Attempt.PresentKey)?.blob ?: defaultKey
        if (!gate.admit(SshWire.ED25519, presented)) {
            throw HostKeyRefused(gate.lastDecision!!, gate.lastPresented)
        }
        val fixture = Fixtures.load("hello.json")
        val response = if (attempt is Attempt.GenerationTwo) fixture.response.withGeneration(2) else fixture.response
        val pipe: LinePipe = FakeLinePipe { line ->
            val request = Json.parseToJsonElement(line).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "hello") {
                assertRequestMatches(fixture.request, line, fixture.name)
                push(withId(response, requestIdOf(line)))
            } else {
                commandReply(request.withoutId())?.let { push(withId(it, requestIdOf(line))) }
            }
        }
        val sessionEventsPipes = mutableListOf<FakeLinePipe>()
        val client = HcClient(
            JsonRpcConnection(pipe, scope),
            {
                eventsPipeFactory().also {
                    sessionEventsPipes += it
                    eventsPipes += it
                }
            },
            scope,
        )
        helloCalls++
        val hello = client.hello()
        sessionsOpened++
        return FakeSession(client, hello, sessionEventsPipes).also { lastSession = it }
    }

    /**
     * A session handed out by [FakeMachineConnector]. `kill` simulates the connection dying; `close` is counted once.
     *
     * @property client the command client.
     * @property hello what `hello` returned.
     */
    inner class FakeSession(
        override val client: HcClient,
        override val hello: HelloResult,
        private val eventsChannels: List<FakeLinePipe>,
    ) : MachineSession {
        private val signal = CompletableDeferred<TransportLost>()
        private var closed = false

        /** How many times the lifecycle reported this session lost through [reportLost]. */
        var reportedLosses: Int = 0
            private set

        override val lost: Deferred<TransportLost> = signal

        override fun reportLost(cause: TransportLost) {
            reportedLosses++
            signal.complete(cause)
        }

        override suspend fun close() {
            if (!closed) {
                closed = true
                sessionsClosed++
                eventsChannels.forEach { it.close() }
            }
            signal.complete(TransportLost("session closed by the app"))
        }

        /**
         * Simulates the connection dying mid-session: the lifecycle sees the loss.
         *
         * @param cause the loss to report.
         */
        fun kill(cause: TransportLost = TransportLost("connection lost")) {
            signal.complete(cause)
        }
    }

    private fun JsonObject.withGeneration(generation: Int): JsonObject {
        val result = getValue("result").jsonObject
        val patched: JsonElement = JsonPrimitive(generation)
        return JsonObject(this + ("result" to JsonObject(result + ("generation" to patched))))
    }
}
