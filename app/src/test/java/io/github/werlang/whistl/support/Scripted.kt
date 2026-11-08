package io.github.werlang.whistl.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One expected exchange with the core: the request the client must write, and the reply to send.
 *
 * @property label where the exchange comes from, for failure messages: a fixture name, or "derived in memory".
 * @property request the request object, id ignored.
 * @property response the reply object; its id is rewritten to the client's id when sent.
 */
class Exchange(
    val label: String,
    val request: JsonObject,
    val response: JsonObject,
)

/**
 * A [FakeLinePipe] that expects exactly [exchanges], in order. Each request must equal the next exchange's request
 * (id ignored); a request after the last exchange fails the test.
 *
 * @param exchanges the exchanges in the order the client must make them.
 * @return the scripted pipe. Its `written` list shows how many requests were made.
 */
fun scriptedPipe(vararg exchanges: Exchange): FakeLinePipe {
    var next = 0
    return FakeLinePipe { line ->
        check(next < exchanges.size) { "unexpected request: the script had ${exchanges.size} exchanges" }
        val exchange = exchanges[next]
        next++
        assertRequestMatches(exchange.request, line, exchange.label)
        push(withId(exchange.response, requestIdOf(line)))
    }
}

/**
 * A command-channel reply function that expects exactly [exchanges], in order, like [scriptedPipe] but for
 * [FakeMachineConnector.commandReply]. Each request (without its id) must equal the next exchange's request; the reply
 * is that exchange's response.
 *
 * @param exchanges the exchanges in the order the client must make them.
 * @return the reply function.
 */
fun scriptedCommands(vararg exchanges: Exchange): (JsonObject) -> JsonObject? {
    var next = 0
    return { request ->
        check(next < exchanges.size) { "unexpected command: the script had ${exchanges.size} exchanges" }
        val exchange = exchanges[next]
        next++
        assertRequestMatches(exchange.request, Json.encodeToString(JsonObject.serializer(), request), exchange.label)
        exchange.response
    }
}

/**
 * Copies this request with `params[key]` set to [value]. Used to derive a request in memory from a recorded one.
 *
 * @param key the param name.
 * @param value the new value.
 * @return the derived request.
 */
fun JsonObject.withParam(key: String, value: JsonElement): JsonObject {
    val params = getValue("params").jsonObject
    return JsonObject(this + ("params" to JsonObject(params + (key to value))))
}

/**
 * Copies this reply with its `result` replaced by [result]. Used to derive a reply in memory from a recorded one.
 *
 * @param result the new `result` object.
 * @return the derived reply.
 */
fun JsonObject.withResult(result: JsonObject): JsonObject = JsonObject(this + ("result" to result))

/** A reply carrying `{ "version": version }`, as `doc.set` returns. Derived in memory; no fixture records it. */
fun versionReply(version: Long): JsonObject = JsonObject(
    mapOf<String, JsonElement>(
        "jsonrpc" to JsonPrimitive("2.0"),
        "result" to JsonObject(mapOf<String, JsonElement>("version" to JsonPrimitive(version))),
    ),
)

/**
 * A reply with a JSON-RPC error object, as hc sends for a failed call. Derived in memory from a recorded error.
 *
 * @param error the `error` object, with `code`, `message` and `data`.
 * @return the reply.
 */
fun errorReply(error: JsonObject): JsonObject = JsonObject(
    mapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "error" to error),
)
