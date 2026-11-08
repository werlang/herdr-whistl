package io.github.werlang.whistl.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * One request/response fixture from `docs/protocol-fixtures/`, as recorded from hc.
 *
 * @property name the file name, for example `hello.json`.
 * @property request the recorded request object, including its `id`.
 * @property response the recorded response object, including its `id`.
 */
data class Fixture(
    val name: String,
    val request: JsonObject,
    val response: JsonObject,
)

/**
 * Reads the recorded protocol fixtures (SPEC §5). Fixtures are read-only: tests never write them.
 *
 * The repository root is the nearest directory at or above the working directory that holds
 * `settings.gradle.kts`. A missing fixture is an error, so the test that needs it fails. Nothing skips.
 */
object Fixtures {
    private const val FIXTURE_DIR = "docs/protocol-fixtures"

    /**
     * The repository root.
     *
     * @return the directory that holds `settings.gradle.kts`.
     * @throws IllegalStateException when no ancestor of the working directory holds it.
     */
    fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found at or above ${File("").absolutePath}; fixtures cannot be located")
    }

    /** The fixture directory. */
    fun directory(): File = File(repoRoot(), FIXTURE_DIR)

    /**
     * Lists every `.json` fixture in the directory.
     *
     * @return the file names, sorted.
     */
    fun names(): List<String> {
        val dir = directory()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: error("fixture directory missing: ${dir.absolutePath}")
        return files.map { it.name }.sorted()
    }

    /**
     * Loads a request/response fixture.
     *
     * @param name the file name, for example `run.json`.
     * @return the request and response objects.
     * @throws IllegalStateException when the file is missing or is not a request/response object.
     */
    fun load(name: String): Fixture {
        val root = parse(name)
        val obj = root as? JsonObject
            ?: error("fixture $name is not a request/response object")
        val request = obj["request"]?.jsonObject ?: error("fixture $name has no request object")
        val response = obj["response"]?.jsonObject ?: error("fixture $name has no response object")
        return Fixture(name, request, response)
    }

    /**
     * Loads a fixture that is an array of notifications (`events_seen.json`).
     *
     * @param name the file name.
     * @return each notification object, in file order.
     * @throws IllegalStateException when the file is missing or is not an array of objects.
     */
    fun loadNotifications(name: String): List<JsonObject> {
        val array = parse(name) as? JsonArray ?: error("fixture $name is not an array of notifications")
        return array.map { it as? JsonObject ?: error("fixture $name holds a non-object element") }
    }

    /**
     * Reads the raw text of a fixture, for example to check a string byte for byte.
     *
     * @param name the file name.
     * @return the file content.
     * @throws IllegalStateException when the file is missing.
     */
    fun text(name: String): String = file(name).readText()

    private fun parse(name: String): JsonElement = Json.parseToJsonElement(text(name))

    private fun file(name: String): File {
        val file = File(directory(), name)
        if (!file.isFile) {
            error("fixture missing: $FIXTURE_DIR/$name (looked in ${directory().absolutePath})")
        }
        return file
    }
}
