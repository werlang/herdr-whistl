package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.ResponseJson
import io.github.werlang.whistl.core.rpc.SubscribeWire
import io.github.werlang.whistl.core.rpc.TerminalEventKind
import io.github.werlang.whistl.core.rpc.TerminalWire
import io.github.werlang.whistl.core.rpc.toDomain
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An agent as the agents list shows it: the terminal, the machine it runs on, and the space it matches (SPEC §1).
 *
 * The name is `MachineAgent` because the domain [Agent] carries only the terminal; the machine and the matched space
 * are added here, and `domain/` is not changed for them.
 *
 * @property machineId the machine the agent runs on.
 * @property agent the agent (its terminal).
 * @property space the matched space, or `null` for "No space".
 */
data class MachineAgent(
    val machineId: String,
    val agent: Agent,
    val space: SpaceRecord?,
) {
    /** The matched space's id, or `null` for "No space". */
    val spaceId: String?
        get() = space?.id
}

/**
 * Applies one events-channel event to the terminals of one machine (SPEC §5, round p3r2).
 *
 * The reducer is pure: it returns a new map and never changes its input. Each rule carries the status of its shape in
 * `docs/event-shapes.md`: observed, recorded, synthetic or assumed. The statuses here match that file.
 *
 * - created (observed): the full terminal object is added under its address, unless it is a folder probe ([isProbe]).
 * - labels (observed): the full label map replaces the old one. A terminal that now carries the probe label leaves the map.
 * - keep (observed): `keep` changes.
 * - title (observed): `title` changes.
 * - status (assumed): `status` changes; an unknown or null value becomes null.
 * - turn (assumed): `turn` changes.
 * - size (assumed): nothing the app models changes.
 * - finished (observed): state finished, with `exit_code`, `finished_ms`, `reason` and `signal`.
 * - removed (observed): the terminal leaves the map.
 * - lost (assumed): state finished with reason `lost`. The shape carries no fields, so this is the documented choice.
 * - restarted (assumed): state live again; exit code, finish time and reason cleared.
 * - missed (synthetic): when `data.terminals` is an array, it replaces the whole map with the snapshot, decoded by the subscribe
 *   result's wire type, without any probe. Without it, nothing changes.
 * - heartbeat (recorded): no change; its line resets the watchdog. doc (recorded): no change here; the repository re-reads the
 *   document. Unknown types: no change. A `foreground` event (not in SPEC §5) parses as unknown and is ignored.
 *
 * An event for an address not in the map changes nothing, and malformed data never throws.
 */
object AgentEventReducer {
    /**
     * Applies [event] to [terminals].
     *
     * @param terminals the terminals of one machine, keyed by address.
     * @param event the parsed event.
     * @return the terminals after the event.
     */
    fun apply(terminals: Map<String, Terminal>, event: HcEvent): Map<String, Terminal> = when (event) {
        is HcEvent.TerminalChanged -> applyTerminal(terminals, event)
        is HcEvent.LabelsChanged -> if (event.labels.containsKey(PROBE_LABEL)) {
            terminals - event.terminal
        } else {
            terminals[event.terminal]
                ?.let { terminals + (event.terminal to it.copy(labels = event.labels)) }
                ?: terminals
        }

        is HcEvent.Missed -> snapshotOf(event.data)?.filterValues { !it.isProbe() } ?: terminals
        is HcEvent.DocChanged, is HcEvent.Heartbeat, is HcEvent.Unknown -> terminals
    }

    private fun applyTerminal(terminals: Map<String, Terminal>, event: HcEvent.TerminalChanged): Map<String, Terminal> {
        val address = event.terminal
        val data = event.data
        when (event.kind) {
            TerminalEventKind.Created -> return createdTerminal(address, data)
                ?.takeUnless { it.isProbe() }
                ?.let { terminals + (address to it) }
                ?: terminals

            TerminalEventKind.Removed -> return terminals - address
            else -> Unit
        }
        val current = terminals[address] ?: return terminals
        val updated = when (event.kind) {
            TerminalEventKind.Keep -> current.copy(keep = data.boolean("keep") ?: current.keep)
            TerminalEventKind.Title -> current.copy(title = data.string("title") ?: current.title)
            TerminalEventKind.Status -> current.copy(status = AgentStatus.fromWire(data.string("status")))
            TerminalEventKind.Turn -> current.copy(turn = data.long("turn") ?: current.turn)
            TerminalEventKind.Finished -> current.copy(
                state = TerminalState.Finished,
                exitCode = data.int("exit_code"),
                finishedMs = data.long("finished_ms"),
                reason = data.string("reason"),
                signal = data.int("signal"),
            )

            TerminalEventKind.Lost -> current.copy(state = TerminalState.Finished, reason = "lost")
            TerminalEventKind.Restarted -> current.copy(
                state = TerminalState.Live,
                exitCode = null,
                finishedMs = null,
                reason = null,
            )

            TerminalEventKind.Size -> return terminals
            TerminalEventKind.Created, TerminalEventKind.Removed -> return terminals
        }
        return terminals + (address to updated)
    }

    /** The terminal from a `created` event's full object, or `null` when the object does not decode. */
    private fun createdTerminal(address: String, data: JsonObject): Terminal? = try {
        ResponseJson.decodeFromJsonElement(TerminalWire.serializer(), data).toDomain().copy(address = address)
    } catch (e: SerializationException) {
        null
    }

    /**
     * The terminals of a `missed` snapshot, keyed by address, decoded with the subscribe result's wire type (phase 6 P0-5). `null`
     * when the event carries no `terminals` array, or it does not decode: then nothing changes.
     */
    private fun snapshotOf(data: JsonObject): Map<String, Terminal>? {
        if (data["terminals"] !is JsonArray) return null
        return try {
            ResponseJson.decodeFromJsonElement(SubscribeWire.serializer(), data)
                .terminals
                .map { it.toDomain() }
                .associateBy { it.address }
        } catch (e: SerializationException) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * Whether the terminal counts as waiting for the user: it is live and its status is `blocked`. This is the one rule the
 * Agents badge counts (SPEC §2), and the screen uses it to place agents too.
 *
 * A finished program cannot be waiting, so a finished terminal whose last status was `blocked` does not count. Neither
 * does a terminal whose state is unknown. The reducer keeps the status on `finished`, so the status alone is not enough.
 *
 * @return `true` when the terminal is live and blocked.
 */
fun Terminal.isWaitingForUser(): Boolean = state == TerminalState.Live && status == AgentStatus.Blocked

/**
 * The order the agents screen shows (SPEC §2). Live agents come first: blocked, working, done, idle, then error, then
 * plain programs with no status. Equal ranks keep their order from the input.
 *
 * Agents that are not live (finished, or with no known state) come after all the live ones, in their input order. Their
 * status is left as the reducer set it, and it does not move them up.
 *
 * @param agents the agents in any order.
 * @return the agents sorted for the screen.
 */
fun sortForAgentsScreen(agents: List<MachineAgent>): List<MachineAgent> {
    val (live, notLive) = agents.partition { it.agent.terminal.state == TerminalState.Live }
    return live.sortedBy { rank(it.agent.terminal.status) } + notLive
}

/**
 * The screen rank of a status: lower shows first. Blocked 0, working 1, done 2, idle 3, error 4, no status 5.
 *
 * @param status the agent's status, or `null` for a plain program.
 * @return the rank.
 */
fun rank(status: AgentStatus?): Int = when (status) {
    AgentStatus.Blocked -> 0
    AgentStatus.Working -> 1
    AgentStatus.Done -> 2
    AgentStatus.Idle -> 3
    AgentStatus.Error -> 4
    null -> 5
}
