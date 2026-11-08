package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcEvent
import io.github.werlang.whistl.core.rpc.ResponseJson
import io.github.werlang.whistl.core.rpc.TerminalEventKind
import io.github.werlang.whistl.core.rpc.TerminalWire
import io.github.werlang.whistl.core.rpc.toDomain
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
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
 * The reducer is pure: it returns a new map and never changes its input. Each rule is marked **observed** when the shape
 * was recorded from hc 0.1.18 (see `event-shapes.md`), or **assumed** when it was not observed and the shape is a
 * documented guess.
 *
 * - created (observed): the full terminal object is added under its address.
 * - labels (observed): the full label map replaces the old one.
 * - keep (observed): `keep` changes.
 * - title (observed): `title` changes.
 * - status (assumed): `status` changes; an unknown or null value becomes null.
 * - turn (assumed): `turn` changes.
 * - size (assumed): nothing the app models changes.
 * - finished (observed): state finished, with `exit_code`, `finished_ms`, `reason` and `signal`.
 * - removed (observed): the terminal leaves the map.
 * - lost (assumed): state finished with reason `lost`. The shape carries no fields, so this is the documented choice.
 * - restarted (assumed): state live again; exit code, finish time and reason cleared.
 * - missed (assumed): when `data.terminals` is present, it replaces the whole map. Without it, nothing changes.
 * - heartbeat, doc, and unknown types: no change. A `foreground` event (not in SPEC §5) parses as unknown and is ignored.
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
        is HcEvent.LabelsChanged -> terminals[event.terminal]
            ?.let { terminals + (event.terminal to it.copy(labels = event.labels)) }
            ?: terminals

        is HcEvent.Missed -> snapshotOf(event.data) ?: terminals
        is HcEvent.DocChanged, is HcEvent.Heartbeat, is HcEvent.Unknown -> terminals
    }

    private fun applyTerminal(terminals: Map<String, Terminal>, event: HcEvent.TerminalChanged): Map<String, Terminal> {
        val address = event.terminal
        val data = event.data
        when (event.kind) {
            TerminalEventKind.Created -> return createdTerminal(address, data)
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

    /** The terminals of a `missed` snapshot, keyed by address, or `null` when the event carries no snapshot. */
    private fun snapshotOf(data: JsonObject): Map<String, Terminal>? {
        val array = data["terminals"] as? JsonArray ?: return null
        return try {
            ResponseJson.decodeFromJsonElement(ListSerializer(TerminalWire.serializer()), array)
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
 * The order the agents screen shows (SPEC §2): blocked, working, done, idle, then error, then plain programs with no
 * status. Equal ranks keep their order from the input.
 *
 * @param agents the agents in any order.
 * @return the agents sorted for the screen.
 */
fun sortForAgentsScreen(agents: List<MachineAgent>): List<MachineAgent> = agents.sortedBy { rank(it.agent.terminal.status) }

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
