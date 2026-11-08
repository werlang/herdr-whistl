package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.PROBE_LABEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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

    /** The home directory `machine.exec` reports for `printenv HOME`. */
    var home: String = "/home/u"

    /** Folders `terminal.run` cannot start a probe in: hc answers `spawn_failed`. */
    val missingFolders: MutableSet<String> = mutableSetOf()

    /**
     * How many `terminal.list` polls a folder probe stays live for. The default, `0`, has it finished on the first poll,
     * so a connection resolves without virtual time passing. `Int.MAX_VALUE` keeps it live (the probe timeout).
     */
    var probeFinishesAfterPolls: Int = 0

    /** The text a folder probe prints, from its folder. Replace it to answer something that is not a path. */
    var probeOutput: (String) -> String = { "$it\n" }

    /** Folder probes started and not yet removed, by address. */
    val probes: MutableMap<String, Probe> = linkedMapOf()

    /** The folder of every probe `terminal.run` started, in order. */
    val probeFolders: MutableList<String> = mutableListOf()

    /** The address of every probe `terminal.rm` removed, in order. */
    val removedProbes: MutableList<String> = mutableListOf()

    /** The method of every command received, in order (`machine.exec` included). */
    val methods: MutableList<String> = mutableListOf()

    private var probeCount = 0

    /**
     * One folder probe on this fake core.
     *
     * @property address the terminal address.
     * @property cwd the folder it was started in.
     * @property finishAfterPolls the poll count at which it reports finished.
     */
    class Probe(val address: String, val cwd: String, val finishAfterPolls: Int) {
        /** The `terminal.list` polls it has been reported in. */
        var polls: Int = 0
    }

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
        val method = request["method"]?.jsonPrimitive?.content
        methods += method.orEmpty()
        return when (method) {
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

            "terminal.run" -> runProbe(params)

            "terminal.read" -> readProbe(params)

            "terminal.rm" -> removeProbe(params)

            "machine.exec" -> machineExec(params)

            "terminal.list" -> if (params["labels"]?.jsonObject?.containsKey(PROBE_LABEL) == true) {
                listProbes()
            } else reply(
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

    /** `terminal.run` of `pwd` with the probe label: starts a probe in its folder, or answers `spawn_failed`. */
    private fun runProbe(params: JsonObject): JsonObject? {
        val argv = params["argv"]?.jsonArray?.map { it.jsonPrimitive.content }
        val isProbe = params["labels"]?.jsonObject?.containsKey(PROBE_LABEL) == true
        if (argv != listOf("pwd") || !isProbe) return null
        val cwd = params.getValue("cwd").jsonPrimitive.content
        if (!cwd.startsWith("/") || cwd in missingFolders) {
            return errorReply(
                JsonObject(
                    mapOf<String, JsonElement>(
                        "code" to JsonPrimitive(-32000),
                        "message" to JsonPrimitive("cannot start \"pwd\": No such file or directory (os error 2)"),
                        "data" to JsonObject(
                            mapOf<String, JsonElement>(
                                "kind" to JsonPrimitive("spawn_failed"),
                                "hint" to JsonPrimitive("check the program name and cwd"),
                            ),
                        ),
                    ),
                ),
            )
        }
        probeFolders += cwd
        val address = "tp${++probeCount}"
        probes[address] = Probe(address, cwd, probeFinishesAfterPolls)
        return reply(
            mapOf<String, JsonElement>(
                "address" to JsonPrimitive(address),
                "cwd" to JsonPrimitive(cwd),
                "state" to JsonPrimitive("live"),
                "labels" to JsonObject(mapOf<String, JsonElement>(PROBE_LABEL to JsonPrimitive("1"))),
            ),
        )
    }

    /** `terminal.list` filtered to the probe label: each probe is reported live until its poll count is reached. */
    private fun listProbes(): JsonObject {
        val listed = probes.values.map { probe ->
            probe.polls += 1
            val state = if (probe.polls >= probe.finishAfterPolls) "finished" else "live"
            JsonObject(
                mapOf<String, JsonElement>(
                    "address" to JsonPrimitive(probe.address),
                    "cwd" to JsonPrimitive(probe.cwd),
                    "state" to JsonPrimitive(state),
                    "labels" to JsonObject(mapOf<String, JsonElement>(PROBE_LABEL to JsonPrimitive("1"))),
                ),
            )
        }
        return reply(
            mapOf<String, JsonElement>(
                "terminals" to JsonArray(listed),
                "machines" to JsonArray(emptyList()),
                "hidden_finished" to JsonPrimitive(0),
            ),
        )
    }

    /** `terminal.read` of a probe: its folder, as `pwd` prints it, with the terminal's trailing padding. */
    private fun readProbe(params: JsonObject): JsonObject? {
        val probe = probes[params.getValue("terminal").jsonPrimitive.content] ?: return null
        return reply(
            mapOf<String, JsonElement>(
                "text" to JsonPrimitive(probeOutput(probe.cwd) + "   "),
                "complete" to JsonPrimitive(true),
                "how" to JsonPrimitive("history"),
            ),
        )
    }

    /** `terminal.rm` of a probe: the probe is gone and its address is recorded. */
    private fun removeProbe(params: JsonObject): JsonObject? {
        val address = params.getValue("terminal").jsonPrimitive.content
        if (probes.remove(address) == null) return null
        removedProbes += address
        return reply(mapOf<String, JsonElement>("removed" to JsonPrimitive(address)))
    }

    /** `machine.exec` of `printenv HOME`: the fake home, base64 as hc sends it. Any other argv is not served. */
    private fun machineExec(params: JsonObject): JsonObject? {
        val argv = params["argv"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (argv != listOf("printenv", "HOME")) return null
        val stdout = java.util.Base64.getEncoder().encodeToString("$home\n".toByteArray(Charsets.UTF_8))
        return reply(
            mapOf<String, JsonElement>(
                "code" to JsonPrimitive(0),
                "ms" to JsonPrimitive(1),
                "signal" to JsonNull,
                "stderr" to JsonPrimitive(""),
                "stdout" to JsonPrimitive(stdout),
                "timed_out" to JsonPrimitive(false),
                "truncated" to JsonPrimitive(false),
            ),
        )
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
