package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.CoreMachine
import io.github.werlang.whistl.domain.HelloResult
import io.github.werlang.whistl.domain.Terminal
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Opens a new line pipe for the events channel. Each call is a separate `hc connect` exec. */
typealias OpenEventsPipe = suspend () -> LinePipe

/**
 * Result of `terminal.list`.
 *
 * @property terminals the terminals matching the request's filters.
 * @property machines the machines hc knows about.
 * @property hiddenFinished how many finished terminals hc did not list.
 * @property skippedTerminals how many entries did not decode and were left out (the others are kept; see `decodeTerminals`).
 */
data class TerminalList(
    val terminals: List<Terminal>,
    val machines: List<CoreMachine>,
    val hiddenFinished: Int,
    val skippedTerminals: Int = 0,
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
 * Result of `terminal.interrupt` (`interrupt.json`: `ctrl+c` was sent and the terminal exited).
 *
 * @property key the key hc sent, for example `ctrl+c`.
 * @property outcome what hc reports happened, for example `exited`.
 */
data class InterruptResult(
    val key: String,
    val outcome: String,
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
 * Result of `terminal.attach` (SPEC §5.1).
 *
 * @property channel the channel number; read it with [FramedSession.channel].
 * @property terminal the terminal attached to.
 */
data class AttachResult(
    val channel: Long,
    val terminal: Terminal,
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
 * An open events subscription (SPEC §5). It owns the connection it reads: [close] ends it, and hc stops feeding it.
 *
 * @property seq the sequence number of the snapshot.
 * @property terminals the snapshot's terminals.
 * @property events the notifications after the snapshot, in arrival order. The flow fails with
 *   [TransportLost] when hc goes silent for 40 s or the channel closes. A line that does not decode is dropped and counted.
 * @param connection the events connection; [close] closes it.
 * @param dropped how many lines were dropped because they did not decode (counted, never logged with content).
 */
class EventSubscription internal constructor(
    val seq: Long,
    val terminals: List<Terminal>,
    val events: Flow<HcEvent>,
    private val connection: JsonRpcConnection,
    private val dropped: () -> Long,
    /** How many snapshot entries did not decode and were left out (see `decodeTerminals`). */
    val skippedTerminals: Int = 0,
) {
    /** Lines dropped so far because they were not event objects. */
    val droppedLines: Long
        get() = dropped()

    /**
     * Closes the events connection. Pending reads end with [TransportLost], and hc stops sending on that channel. Safe to call
     * more than once.
     */
    suspend fun close() {
        connection.close()
    }
}

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
        val decoded = decodeTerminals(wire.terminals)
        return TerminalList(
            terminals = decoded.terminals,
            machines = wire.machines.map { it.toDomain() },
            hiddenFinished = wire.hiddenFinished,
            skippedTerminals = decoded.skipped,
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
     * @param force `true` to send to a blocked program; omitted from the request when `null`.
     * @throws Blocked when the program is blocked and [force] is not `true`.
     */
    suspend fun terminalSend(terminal: String, text: String, force: Boolean? = null): TerminalSent {
        val wire = command.typed<TerminalSendParams, TerminalSendWire>(
            "terminal.send",
            TerminalSendParams(terminal = terminal, text = text, force = force),
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
     * Calls `terminal.interrupt`: sends Ctrl+C to the terminal (recorded in `interrupt.json`).
     *
     * @param terminal the terminal address.
     * @return the key hc sent and the outcome it reports.
     */
    suspend fun terminalInterrupt(terminal: String): InterruptResult {
        val wire = command.typed<TerminalInterruptParams, TerminalInterruptWire>(
            "terminal.interrupt",
            TerminalInterruptParams(terminal = terminal),
        )
        return InterruptResult(key = wire.key, outcome = wire.outcome)
    }

    /**
     * Calls `terminal.attach` on the framed connection (SPEC §5.1). The reply names the channel. The program's output comes on
     * it, and the viewer's frames go to it.
     *
     * @param terminal the terminal address.
     * @param cols the viewer's width in cells, 0 to 65535.
     * @param rows the viewer's height in cells, 0 to 65535.
     * @param display the viewer's colours and cell size; omitted when `null`.
     * @param history scrollback lines to show first, main screen only; omitted when `null`.
     * @return the channel and the terminal.
     * @throws NeedsFramed when the connection is not framed.
     * @throws IllegalArgumentException when a size is not a u16.
     */
    suspend fun terminalAttach(
        terminal: String,
        cols: Int,
        rows: Int,
        display: ViewerDisplay? = null,
        history: Int? = null,
        resumed: Boolean? = null,
    ): AttachResult {
        require(cols in 0..MAX_CELLS && rows in 0..MAX_CELLS) { "size is not a u16" }
        val wire = command.typed<TerminalAttachParams, AttachWire>(
            "terminal.attach",
            TerminalAttachParams(
                terminal = terminal,
                cols = cols,
                rows = rows,
                display = display?.toWire(),
                history = history,
                resumed = resumed,
            ),
        )
        return AttachResult(channel = wire.channel, terminal = wire.terminal.toDomain())
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
        val snapshot = try {
            events.typed<EventsSubscribeParams, SubscribeWire>(
                "events.subscribe",
                EventsSubscribeParams(labels = labels),
            )
        } catch (e: Throwable) {
            // A subscribe that fails or times out must not leave its connection open: hc keeps feeding a channel nobody reads
            // (phase 6 P0-6). Whatever ended the call, the connection is closed before the error goes on.
            withContext(NonCancellable) { events.close() }
            throw e
        }
        val dropped = AtomicLong(0L)
        // The events channel carries `event`, the top-level `heartbeat` and the top-level `missed` (phase 6 P0-5). Anything
        // else on it is not an event and is ignored without being counted.
        val stream: Flow<HcEvent> = events.notifications
            .filter { it.method in EVENT_METHODS }
            .mapNotNull { notification ->
                HcEvent.fromNotificationOrNull(notification.method, notification.params) ?: run {
                    dropped.incrementAndGet()
                    null
                }
            }
        val decoded = decodeTerminals(snapshot.terminals)
        return EventSubscription(
            seq = snapshot.seq,
            terminals = decoded.terminals,
            events = stream,
            connection = events,
            dropped = dropped::get,
            skippedTerminals = decoded.skipped,
        )
    }

    private companion object {
        /** The only protocol generation this client speaks (SPEC §5). */
        const val SUPPORTED_GENERATION = 1

        /** The largest size a `u16` field holds, in cells. */
        const val MAX_CELLS = 0xFFFF

        /** The notifications of the events channel that are events (SPEC §5, `docs/event-shapes.md`). */
        val EVENT_METHODS: Set<String> = setOf(HcEvent.EVENT_METHOD, HcEvent.HEARTBEAT_METHOD, HcEvent.MISSED_METHOD)
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
