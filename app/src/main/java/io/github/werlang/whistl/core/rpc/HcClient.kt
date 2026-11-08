package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.CoreMachine
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64

/** Opens a new line pipe for the events channel. Each call is a separate `hc connect` exec. */
typealias OpenEventsPipe = suspend () -> LinePipe

/**
 * Result of `terminal.list`.
 *
 * @property terminals the terminals matching the request's filters.
 * @property machines the machines hc knows about.
 * @property hiddenFinished how many finished terminals hc did not list.
 */
data class TerminalList(
    val terminals: List<Terminal>,
    val machines: List<CoreMachine>,
    val hiddenFinished: Int,
)

/**
 * Result of `terminal.read`.
 *
 * @property text the terminal text, as hc returns it.
 * @property complete whether the text is the whole requested history.
 * @property how how hc produced the text, for example `history`.
 */
data class TerminalRead(
    val text: String,
    val complete: Boolean,
    val how: String?,
)

/**
 * Result of `terminal.send`.
 *
 * @property started the start time hc reports, if any.
 * @property status the agent status after the send, or `null`.
 * @property turn the turn counter after the send.
 */
data class TerminalSent(
    val started: Long?,
    val status: AgentStatus?,
    val turn: Long,
)

/**
 * Result of `terminal.keys`.
 *
 * @property status the agent status after the keys, or `null`.
 * @property turn the turn counter after the keys.
 */
data class KeysSent(
    val status: AgentStatus?,
    val turn: Long,
)

/**
 * Result of `machine.exec`. Output is decoded from base64 to UTF-8 (SPEC §5).
 *
 * @property code the process exit code.
 * @property stdout the decoded standard output.
 * @property stderr the decoded standard error.
 * @property timedOut whether hc stopped the command for taking too long.
 * @property truncated whether hc cut the output.
 */
data class ExecResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/**
 * Result of `doc.get`.
 *
 * @property value the document, or `null` when it was never written.
 * @property version the document version; `0` when never written.
 */
data class DocValue(
    val value: JsonElement?,
    val version: Long,
)

/**
 * An open events subscription (SPEC §5).
 *
 * @property seq the sequence number of the snapshot.
 * @property terminals the snapshot's terminals.
 * @property events the notifications after the snapshot, in arrival order. The flow fails with
 *   [TransportLost] when hc goes silent for 40 s or the channel closes.
 */
data class EventSubscription(
    val seq: Long,
    val terminals: List<Terminal>,
    val events: Flow<HcEvent>,
)

/**
 * The typed client for herdr-core's methods (SPEC §5).
 *
 * Every method takes plain values, builds a typed request (see `Wire.kt`) and maps the result to the domain.
 * No method takes or builds a shell string: `argv` is always a list.
 *
 * `terminal.run` always sends `no_parent: true` and `no_notify: true`. Neither has a parameter, so a caller
 * cannot change them.
 *
 * @param command the connection for command calls.
 * @param openEventsPipe opens the events channel; [eventsSubscribe] wraps it in its own connection.
 * @param scope the scope for the events connection's reader.
 */
class HcClient(
    private val command: JsonRpcConnection,
    private val openEventsPipe: OpenEventsPipe,
    private val scope: CoroutineScope,
) {
    /**
     * Calls `hello` and checks the protocol generation.
     *
     * @return the core's identity and capabilities.
     * @throws UnsupportedGeneration when hc reports a generation other than 1.
     */
    suspend fun hello(): HelloResult {
        val wire = command.typed<NoParams, HelloWire>("hello", NoParams)
        if (wire.generation != SUPPORTED_GENERATION) throw UnsupportedGeneration(wire.generation)
        return HelloResult(
            coreId = wire.core.id,
            coreName = wire.core.name,
            features = wire.features,
            generation = wire.generation,
            methods = wire.methods,
            version = wire.version,
        )
    }

    /**
     * Calls `terminal.list`.
     *
     * @param labels optional label filter; omitted from the request when `null`.
     * @param all optional flag to include finished terminals; omitted when `null`.
     */
    suspend fun terminalList(labels: Map<String, String>? = null, all: Boolean? = null): TerminalList {
        val wire = command.typed<TerminalListParams, TerminalListWire>(
            "terminal.list",
            TerminalListParams(labels = labels, all = all),
        )
        return TerminalList(
            terminals = wire.terminals.map { it.toDomain() },
            machines = wire.machines.map { it.toDomain() },
            hiddenFinished = wire.hiddenFinished,
        )
    }

    /**
     * Calls `terminal.run`, always with `no_parent` and `no_notify` set to `true`.
     *
     * @param argv the program and its arguments; a list, never a shell string.
     * @param cwd the working directory on the core.
     * @param labels the labels to set, for example `whistl.space`.
     * @param name optional display name; omitted when `null`.
     * @param keep optional keep flag; omitted when `null`.
     * @return the new terminal.
     */
    suspend fun terminalRun(
        argv: List<String>,
        cwd: String,
        labels: Map<String, String>,
        name: String? = null,
        keep: Boolean? = null,
    ): Terminal {
        val wire = command.typed<TerminalRunParams, TerminalWire>(
            "terminal.run",
            TerminalRunParams(argv = argv, cwd = cwd, labels = labels, name = name, keep = keep),
        )
        return wire.toDomain()
    }

    /**
     * Calls `terminal.read`.
     *
     * @param terminal the terminal address.
     * @param lines how many lines to read; omitted from the request when `null`.
     */
    suspend fun terminalRead(terminal: String, lines: Int? = null): TerminalRead {
        val wire = command.typed<TerminalReadParams, TerminalReadWire>(
            "terminal.read",
            TerminalReadParams(terminal = terminal, lines = lines),
        )
        return TerminalRead(text = wire.text, complete = wire.complete, how = wire.how)
    }

    /**
     * Calls `terminal.send`: pastes [text] and presses Enter.
     *
     * @param terminal the terminal address.
     * @param text the text to send.
     */
    suspend fun terminalSend(terminal: String, text: String): TerminalSent {
        val wire = command.typed<TerminalSendParams, TerminalSendWire>(
            "terminal.send",
            TerminalSendParams(terminal = terminal, text = text),
        )
        return TerminalSent(started = wire.started, status = AgentStatus.fromWire(wire.status), turn = wire.turn)
    }

    /**
     * Calls `terminal.keys`.
     *
     * @param terminal the terminal address.
     * @param keys key names such as `ctrl+c`, `up`, `enter`.
     */
    suspend fun terminalKeys(terminal: String, keys: List<String>): KeysSent {
        val wire = command.typed<TerminalKeysParams, TerminalKeysWire>(
            "terminal.keys",
            TerminalKeysParams(terminal = terminal, keys = keys),
        )
        return KeysSent(status = AgentStatus.fromWire(wire.status), turn = wire.turn)
    }

    /**
     * Calls `terminal.interrupt`. **No fixture records this method**: the request shape is covered by a
     * hand-written expectation, and the result is not parsed.
     *
     * @param terminal the terminal address.
     */
    suspend fun terminalInterrupt(terminal: String) {
        command.typed<TerminalInterruptParams, JsonElement>(
            "terminal.interrupt",
            TerminalInterruptParams(terminal = terminal),
        )
    }

    /**
     * Calls `terminal.kill`.
     *
     * @param terminal the terminal address.
     * @return the terminal, now finished.
     */
    suspend fun terminalKill(terminal: String): Terminal {
        val wire = command.typed<TerminalKillParams, TerminalWire>(
            "terminal.kill",
            TerminalKillParams(terminal = terminal),
        )
        return wire.toDomain()
    }

    /**
     * Calls `terminal.rm`.
     *
     * @param terminal the terminal address.
     * @return the address hc removed.
     */
    suspend fun terminalRm(terminal: String): String {
        val wire = command.typed<TerminalRmParams, TerminalRemovedWire>(
            "terminal.rm",
            TerminalRmParams(terminal = terminal),
        )
        return wire.removed
    }

    /**
     * Calls `terminal.label`.
     *
     * @param terminal the terminal address.
     * @param labels the labels to set on the terminal.
     * @return the terminal with its labels.
     */
    suspend fun terminalLabel(terminal: String, labels: Map<String, String>): Terminal {
        val wire = command.typed<TerminalLabelParams, TerminalWire>(
            "terminal.label",
            TerminalLabelParams(terminal = terminal, labels = labels),
        )
        return wire.toDomain()
    }

    /**
     * Calls `machine.exec`. `stdout` and `stderr` are base64 on the wire and are decoded here as UTF-8.
     *
     * @param argv the program and its arguments; a list, never a shell string.
     * @return the exit code and decoded output.
     * @throws IllegalArgumentException when the wire output is not valid base64.
     */
    suspend fun machineExec(argv: List<String>): ExecResult {
        val wire = command.typed<MachineExecParams, MachineExecWire>(
            "machine.exec",
            MachineExecParams(argv = argv),
        )
        return ExecResult(
            code = wire.code,
            stdout = decodeBase64Utf8(wire.stdout),
            stderr = decodeBase64Utf8(wire.stderr),
            timedOut = wire.timedOut,
            truncated = wire.truncated,
        )
    }

    /**
     * Calls `doc.get`.
     *
     * @param name the document name.
     * @return the value (or `null`) and its version (`0` when never written).
     */
    suspend fun docGet(name: String): DocValue {
        val wire = command.typed<DocGetParams, DocGetWire>("doc.get", DocGetParams(name = name))
        return DocValue(value = wire.value, version = wire.version)
    }

    /**
     * Calls `doc.set`: a compare-and-set against [version].
     *
     * @param name the document name.
     * @param value the new document value.
     * @param version the version the caller last read.
     * @return the new version.
     * @throws Conflict when the document is at a different version.
     */
    suspend fun docSet(name: String, value: JsonElement, version: Long): Long {
        val wire = command.typed<DocSetParams, DocSetWire>(
            "doc.set",
            DocSetParams(name = name, value = value, version = version),
        )
        return wire.version
    }

    /**
     * Calls `events.subscribe` on its own connection, because the subscription takes that connection over.
     *
     * The events connection has a 40 s watchdog ([EVENTS_IDLE_TIMEOUT_MS]). The returned flow carries only
     * `event` notifications, parsed into [HcEvent]; any other method is dropped.
     *
     * @param labels optional label filter; omitted when `null`.
     * @return the snapshot and the event stream.
     */
    suspend fun eventsSubscribe(labels: Map<String, String>? = null): EventSubscription {
        val events = JsonRpcConnection(
            pipe = openEventsPipe(),
            scope = scope,
            idleTimeoutMillis = EVENTS_IDLE_TIMEOUT_MS,
        )
        val snapshot = events.typed<EventsSubscribeParams, SubscribeWire>(
            "events.subscribe",
            EventsSubscribeParams(labels = labels),
        )
        val stream: Flow<HcEvent> = events.notifications
            .filter { it.method == EVENT_METHOD }
            .map { HcEvent.fromParams(it.params) }
        return EventSubscription(
            seq = snapshot.seq,
            terminals = snapshot.terminals.map { it.toDomain() },
            events = stream,
        )
    }

    private companion object {
        /** The only protocol generation this client speaks (SPEC §5). */
        const val SUPPORTED_GENERATION = 1

        /** The notification name hc uses for events (SPEC §5). */
        const val EVENT_METHOD = "event"
    }
}

/**
 * Encodes [params] as the typed request, calls [method] on this connection, and decodes the result as [R].
 * Request encoding is strict and response decoding is lenient (see `Wire.kt`).
 */
private suspend inline fun <reified P, reified R> JsonRpcConnection.typed(method: String, params: P): R {
    val result = call(method, RequestJson.encodeToJsonElement(params))
    return ResponseJson.decodeFromJsonElement(result)
}

/** Decodes base64 wire output as UTF-8 text. */
private fun decodeBase64Utf8(encoded: String): String =
    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
