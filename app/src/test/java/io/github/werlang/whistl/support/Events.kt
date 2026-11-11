package io.github.werlang.whistl.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A terminal object shaped as hc 0.1.18 sends it (SPEC §5; see `docs/event-shapes.md`). */
fun terminalJson(
    address: String,
    state: String = "live",
    status: String? = null,
    // A space label by default: the Agents list only shows terminals a space holds (0.6.2).
    labels: Map<String, String> = mapOf("whistl.space" to "web"),
    cwd: String? = null,
    title: String = "",
    keep: Boolean = false,
    turn: Long = 0,
): JsonObject = JsonObject(
    mapOf<String, JsonElement>(
        "address" to JsonPrimitive(address),
        "name" to JsonPrimitive(address),
        "argv" to JsonArray(listOf(JsonPrimitive("sh"))),
        "cwd" to (cwd?.let { JsonPrimitive(it) } ?: JsonNull),
        "labels" to JsonObject(labels.mapValues { JsonPrimitive(it.value) }),
        "state" to JsonPrimitive(state),
        "status" to (status?.let { JsonPrimitive(it) } ?: JsonNull),
        "status_ms" to JsonNull,
        "turn" to JsonPrimitive(turn),
        "title" to JsonPrimitive(title),
        "keep" to JsonPrimitive(keep),
        "exit_code" to JsonNull,
        "created_ms" to JsonPrimitive(1794006620358L),
        "finished_ms" to JsonNull,
        "pid" to JsonPrimitive(4242),
        "signal" to JsonNull,
        "reason" to JsonNull,
        "cols" to JsonPrimitive(200),
        "rows" to JsonPrimitive(120),
    ),
)

/** A `events.subscribe` reply: the snapshot with its sequence number and the terminals. */
fun eventsSnapshot(seq: Long, terminals: List<JsonObject>): JsonObject = JsonObject(
    mapOf<String, JsonElement>(
        "jsonrpc" to JsonPrimitive("2.0"),
        "result" to JsonObject(
            mapOf<String, JsonElement>(
                "channel" to JsonNull,
                "seq" to JsonPrimitive(seq),
                "terminals" to JsonArray(terminals),
            ),
        ),
    ),
)

/**
 * One `event` notification line: `{"method":"event","params":{type, terminal, seq, data}}`.
 *
 * @param type the event type, for example `finished`.
 * @param terminal the terminal address, or `null` for an event that names none.
 * @param seq the sequence number.
 * @param data the event's `data` object.
 */
fun eventLine(type: String, terminal: String?, seq: Long, data: JsonObject): String = Json.encodeToString(
    JsonObject.serializer(),
    JsonObject(
        mapOf<String, JsonElement>(
            "jsonrpc" to JsonPrimitive("2.0"),
            "method" to JsonPrimitive("event"),
            "params" to JsonObject(
                mapOf<String, JsonElement>(
                    "type" to JsonPrimitive(type),
                    "terminal" to (terminal?.let { JsonPrimitive(it) } ?: JsonNull),
                    "seq" to JsonPrimitive(seq),
                    "data" to data,
                ),
            ),
        ),
    ),
)

/** An events channel that answers `events.subscribe` with [snapshot] and then takes pushed notifications. */
fun snapshotPipe(snapshot: JsonObject): FakeLinePipe = FakeLinePipe { line ->
    push(withId(snapshot, requestIdOf(line)))
}

/** A `data` object with the given entries. */
fun dataOf(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

/**
 * One space as stored in the `whistl` document (SPEC §5).
 *
 * @param id the space id.
 * @param name the name.
 * @param folder the folder.
 * @param pinned the pin state.
 * @param order the order.
 * @param extra unknown fields to store alongside.
 */
fun spaceJson(
    id: String,
    name: String,
    folder: String,
    pinned: Boolean = false,
    order: Int = 0,
    extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = JsonObject(
    extra + mapOf<String, JsonElement>(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(name),
        "folder" to JsonPrimitive(folder),
        "pinned" to JsonPrimitive(pinned),
        "order" to JsonPrimitive(order),
    ),
)

/**
 * A `whistl` document value (SPEC §5): `{ "v": 1, "spaces": [...] }` plus any unknown top-level fields.
 *
 * @param spaces the spaces.
 * @param extra unknown top-level fields.
 */
fun spacesDocJson(spaces: List<JsonObject>, extra: Map<String, JsonElement> = emptyMap()): JsonObject = JsonObject(
    extra + mapOf<String, JsonElement>("v" to JsonPrimitive(1), "spaces" to JsonArray(spaces)),
)
