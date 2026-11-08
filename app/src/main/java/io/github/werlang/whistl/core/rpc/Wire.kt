package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.CoreMachine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The JSON used to write requests. Strict: each request type is a class with declared fields only.
 *
 * `encodeDefaults = true` writes fixed fields such as `no_parent`. `explicitNulls = false` leaves out an
 * optional field that is `null`, so an unset optional never appears on the wire.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val RequestJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The JSON used to read lines from hc. Lenient: unknown keys are ignored, because hc may add fields.
 */
internal val ResponseJson: Json = Json {
    ignoreUnknownKeys = true
}

// ---- Envelope (both directions) ----

/**
 * An outgoing JSON-RPC 2.0 request. Strict: only `jsonrpc`, `id`, `method` and `params` are ever written.
 */
@Serializable
internal class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement,
)

/**
 * One incoming line: a response (has `id`, with `result` or `error`) or a notification (has `method`, no `id`).
 *
 * Lenient: hc may add fields, so unknown keys are ignored.
 */
@Serializable
internal class RpcEnvelope(
    val id: Long? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: RpcErrorWire? = null,
)

/** The `error` object of a JSON-RPC response. Lenient: unknown keys are ignored. */
@Serializable
internal class RpcErrorWire(
    val code: Int = 0,
    val message: String = "",
    val data: RpcErrorDataWire? = null,
)

/**
 * The `error.data` object hc sends: a stable `kind` and a `hint` (SPEC §5). Lenient: unknown keys are ignored.
 */
@Serializable
internal class RpcErrorDataWire(
    val kind: String? = null,
    val hint: String? = null,
)

/** Maps a JSON-RPC error to the typed [HcException] for its `kind` (SPEC §5). */
internal fun RpcErrorWire.toException(): HcException {
    val kind = data?.kind
    val hint = data?.hint
    return when {
        kind == "not_found" -> NotFound(message, hint)
        kind == "conflict" -> Conflict(message, hint)
        kind == "spawn_failed" -> SpawnFailed(message, hint)
        kind == "live" -> StillLive(message, hint)
        kind == "blocked" -> Blocked(message, hint)
        kind == "invalid_request" || (kind == null && code == JSON_RPC_INVALID_REQUEST) ->
            InvalidRequest(message, hint)
        else -> Unknown(kind, code, message, hint)
    }
}

/** The JSON-RPC standard code for an invalid request. */
internal const val JSON_RPC_INVALID_REQUEST: Int = -32600

// ---- Request params (strict: declared fields only) ----

/** Params with no fields. Encodes as `{}`. */
@Serializable
internal object NoParams

/** `terminal.list`. Both fields are optional and omitted when unset. */
@Serializable
internal class TerminalListParams(
    val labels: Map<String, String>? = null,
    val all: Boolean? = null,
)

/**
 * `terminal.run`. The fixed flags `no_parent` and `no_notify` are always `true` and cannot be changed by a caller.
 */
@Serializable
internal class TerminalRunParams(
    val argv: List<String>,
    val cwd: String,
    val labels: Map<String, String>,
    val name: String? = null,
    val keep: Boolean? = null,
    @SerialName("no_parent") val noParent: Boolean = true,
    @SerialName("no_notify") val noNotify: Boolean = true,
)

/** `terminal.read`. `lines` is optional: omitted when unset. */
@Serializable
internal class TerminalReadParams(
    val terminal: String,
    val lines: Int? = null,
)

/**
 * `terminal.send`. `force` is sent only when set: hc refuses a blocked program without it (kind `blocked`), and a request
 * without the field must stay byte-for-byte what `send.json` records.
 */
@Serializable
internal class TerminalSendParams(
    val terminal: String,
    val text: String,
    val force: Boolean? = null,
)

/** `terminal.keys`. */
@Serializable
internal class TerminalKeysParams(
    val terminal: String,
    val keys: List<String>,
)

/** `terminal.interrupt`: the terminal id only (`interrupt.json`). */
@Serializable
internal class TerminalInterruptParams(val terminal: String)

/** `terminal.kill`. */
@Serializable
internal class TerminalKillParams(val terminal: String)

/** `terminal.rm`. */
@Serializable
internal class TerminalRmParams(val terminal: String)

/** `terminal.label`. */
@Serializable
internal class TerminalLabelParams(
    val terminal: String,
    val labels: Map<String, String>,
)

/** `machine.exec`. The argv is a list; nothing is built into a shell string here. */
@Serializable
internal class MachineExecParams(val argv: List<String>)

/** `doc.get`. */
@Serializable
internal class DocGetParams(val name: String)

/** `doc.set`. `version` is the version the caller last read: a compare-and-set. */
@Serializable
internal class DocSetParams(
    val name: String,
    val value: JsonElement,
    val version: Long,
)

/** `events.subscribe`. The labels filter is optional and omitted when unset. */
@Serializable
internal class EventsSubscribeParams(val labels: Map<String, String>? = null)

// ---- Results and notifications (lenient) ----

/** The `core` object inside `hello`. Lenient: unknown keys are ignored. */
@Serializable
internal class CoreWire(
    val id: String = "",
    val name: String = "",
)

/** `hello` result. Lenient: unknown keys are ignored. */
@Serializable
internal class HelloWire(
    val core: CoreWire = CoreWire(),
    val features: List<String> = emptyList(),
    val generation: Int = 0,
    val methods: List<String> = emptyList(),
    val version: String = "",
)

/**
 * A `Terminal` object as hc sends it. Lenient: unknown keys are ignored, because hc may add fields.
 * Converted to the domain [Terminal] by [toDomain].
 */
@Serializable
internal class TerminalWire(
    val address: String = "",
    val name: String? = null,
    val argv: List<String> = emptyList(),
    val cwd: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val state: String? = null,
    val status: String? = null,
    val turn: Long = 0,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("created_ms") val createdMs: Long? = null,
    @SerialName("finished_ms") val finishedMs: Long? = null,
    val title: String? = null,
    val pid: Long? = null,
    val signal: Int? = null,
    val reason: String? = null,
    val keep: Boolean = false,
)

/** A `machine` object inside `terminal.list` and `machine.list`. Lenient: unknown keys are ignored. */
@Serializable
internal class MachineWire(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val target: String = "",
    val state: String = "",
    val version: String? = null,
    val session: String? = null,
    val temporary: Boolean = false,
    val via: Boolean = false,
)

/** `terminal.list` result. Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalListWire(
    val terminals: List<TerminalWire> = emptyList(),
    val machines: List<MachineWire> = emptyList(),
    @SerialName("hidden_finished") val hiddenFinished: Int = 0,
)

/** `machine.list` result. Lenient: unknown keys are ignored. */
@Serializable
internal class MachineListWire(
    val machines: List<MachineWire> = emptyList(),
)

/** `terminal.read` result. Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalReadWire(
    val text: String = "",
    val complete: Boolean = false,
    val how: String? = null,
)

/** `terminal.send` result. Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalSendWire(
    val started: Long? = null,
    val status: String? = null,
    val turn: Long = 0,
)

/** `terminal.keys` result. Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalKeysWire(
    val status: String? = null,
    val turn: Long = 0,
)

/** `terminal.interrupt` result (`interrupt.json`). Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalInterruptWire(
    val key: String = "",
    val outcome: String = "",
)

/** `terminal.rm` result. Lenient: unknown keys are ignored. */
@Serializable
internal class TerminalRemovedWire(
    val removed: String = "",
)

/**
 * `machine.exec` result. `stdout` and `stderr` are base64 (SPEC §5). Lenient: unknown keys are ignored.
 */
@Serializable
internal class MachineExecWire(
    val code: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("timed_out") val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** `doc.get` result. `value` is `null` when the document was never written. Lenient: unknown keys are ignored. */
@Serializable
internal class DocGetWire(
    val value: JsonElement? = null,
    val version: Long = 0,
)

/** `doc.set` result. Lenient: unknown keys are ignored. */
@Serializable
internal class DocSetWire(
    val version: Long = 0,
)

/** `events.subscribe` snapshot. Lenient: unknown keys are ignored. */
@Serializable
internal class SubscribeWire(
    val seq: Long = 0,
    val terminals: List<TerminalWire> = emptyList(),
)

/**
 * The `params` of an `event` notification: `type`, `terminal`, `seq`, `data`. Lenient: unknown keys are ignored.
 * `type` may be a value this client does not know; [HcEvent] keeps those as `Unknown`.
 */
@Serializable
internal class EventWire(
    val type: String = "",
    val terminal: String? = null,
    val seq: Long? = null,
    val data: JsonObject? = null,
)

// ---- Mapping to the domain ----

/** Converts a wire terminal to the domain [Terminal]. Unknown state and status values become `null`. */
internal fun TerminalWire.toDomain(): Terminal = Terminal(
    address = address,
    name = name,
    argv = argv,
    cwd = cwd,
    labels = labels,
    state = TerminalState.fromWire(state),
    status = AgentStatus.fromWire(status),
    turn = turn,
    exitCode = exitCode,
    createdMs = createdMs,
    finishedMs = finishedMs,
    title = title,
    pid = pid,
    signal = signal,
    reason = reason,
    keep = keep,
)

/** Converts a wire machine to the domain [CoreMachine]. */
internal fun MachineWire.toDomain(): CoreMachine = CoreMachine(
    id = id,
    name = name,
    host = host,
    target = target,
    state = state,
    version = version,
    session = session,
    temporary = temporary,
    via = via,
)
