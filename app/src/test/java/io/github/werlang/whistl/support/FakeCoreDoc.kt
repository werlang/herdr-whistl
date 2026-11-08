package io.github.werlang.whistl.support

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * An in-memory core for the `whistl` document and the terminal list, with hc's compare-and-set rule: a `doc.set` whose
 * version is not the stored one fails with `conflict` (SPEC §5). Plugged into [FakeMachineConnector.commandReply].
 *
 * @param initialValue the document value at the start, or `null` for a never-written document.
 * @param initialVersion the version at the start.
 */
class FakeCoreDoc(initialValue: JsonObject? = null, initialVersion: Long = 1) {
    /** The stored document value, or `null` when never written. */
    var value: JsonObject? = initialValue

    /** The stored version. */
    var version: Long = initialVersion

    /** Terminals `terminal.list` returns. */
    val terminals: MutableList<JsonObject> = mutableListOf()

    /** Addresses killed through `terminal.kill`, in order. */
    val killed: MutableList<String> = mutableListOf()

    /**
     * Runs just before each `doc.set` is checked. A test uses it to play a concurrent writer: it changes [value] and
     * bumps [version], so the pending write conflicts.
     */
    var beforeSet: (FakeCoreDoc) -> Unit = {}

    /**
     * Answers one command-channel request (without its `id`).
     *
     * @param request the request object.
     * @return the reply object, or `null` for a method this fake does not serve.
     */
    fun handle(request: JsonObject): JsonObject? {
        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
        return when (request["method"]?.jsonPrimitive?.content) {
            "doc.get" -> reply(
                mapOf<String, JsonElement>("value" to (value ?: JsonNull), "version" to JsonPrimitive(version)),
            )

            "doc.set" -> {
                beforeSet(this)
                val sent = params["version"]?.jsonPrimitive?.long
                if (sent == version) {
                    value = params["value"]?.jsonObject
                    version += 1
                    reply(mapOf<String, JsonElement>("version" to JsonPrimitive(version)))
                } else {
                    conflict(sent)
                }
            }

            "terminal.list" -> reply(
                mapOf<String, JsonElement>(
                    "terminals" to JsonArray(terminals),
                    "machines" to JsonArray(emptyList()),
                    "hidden_finished" to JsonPrimitive(0),
                ),
            )

            "terminal.kill" -> {
                val address = params.getValue("terminal").jsonPrimitive.content
                killed += address
                reply(mapOf<String, JsonElement>("address" to JsonPrimitive(address)))
            }

            else -> null
        }
    }

    private fun reply(result: Map<String, JsonElement>): JsonObject =
        JsonObject(mapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "result" to JsonObject(result)))

    private fun conflict(sent: Long?): JsonObject = errorReply(
        JsonObject(
            mapOf<String, JsonElement>(
                "code" to JsonPrimitive(-32000),
                "message" to JsonPrimitive("document \"whistl\" is at version $version, not $sent"),
                "data" to JsonObject(
                    mapOf<String, JsonElement>(
                        "kind" to JsonPrimitive("conflict"),
                        "hint" to JsonPrimitive("read doc.get and retry with its version"),
                    ),
                ),
            ),
        ),
    )
}
