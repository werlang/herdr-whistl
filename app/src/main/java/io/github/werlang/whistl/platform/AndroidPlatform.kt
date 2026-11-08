package io.github.werlang.whistl.platform

import android.content.Context
import io.github.werlang.whistl.core.ssh.HostKeyStore
import io.github.werlang.whistl.core.ssh.PinnedHostKey
import io.github.werlang.whistl.data.AuthMethod
import io.github.werlang.whistl.data.MachineStore
import io.github.werlang.whistl.data.SettingsStore
import io.github.werlang.whistl.data.SpacesCache
import io.github.werlang.whistl.data.SpacesDocument
import io.github.werlang.whistl.data.StoredMachine
import io.github.werlang.whistl.domain.Clock
import io.github.werlang.whistl.domain.Machine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The app's monotonic clock: the throttle of [io.github.werlang.whistl.data.InstalledAgentsDetector] reads it.
 * `elapsedRealtime` is not affected by changes to the wall clock.
 */
object AndroidClock : Clock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * Machines in private SharedPreferences, as one JSON array in insertion order. No secrets: a password is in
 * `SecretStore`, and a key is `KeyManager`'s.
 *
 * @param context any context; the application context is used.
 */
class SharedPreferencesMachineStore(context: Context) : MachineStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun list(): List<StoredMachine> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { decode(it.jsonObject) }
    }

    override fun put(stored: StoredMachine) {
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.machine.id == stored.machine.id }
        if (index >= 0) current[index] = stored else current += stored
        save(current)
    }

    override fun delete(id: String) {
        save(list().filterNot { it.machine.id == id })
    }

    private fun save(machines: List<StoredMachine>) {
        val array = JsonArray(machines.map { encode(it) })
        prefs.edit().putString(KEY, Json.encodeToString(JsonArray.serializer(), array)).apply()
    }

    private fun encode(stored: StoredMachine): JsonObject = JsonObject(
        mapOf<String, JsonElement>(
            "id" to JsonPrimitive(stored.machine.id),
            "name" to JsonPrimitive(stored.machine.name),
            "host" to JsonPrimitive(stored.machine.host),
            "port" to JsonPrimitive(stored.machine.port),
            "user" to JsonPrimitive(stored.machine.user),
            "auth" to JsonPrimitive(stored.auth.name),
        ),
    )

    private fun decode(obj: JsonObject): StoredMachine = StoredMachine(
        machine = Machine(
            id = obj.getValue("id").jsonPrimitive.content,
            name = obj.getValue("name").jsonPrimitive.content,
            host = obj.getValue("host").jsonPrimitive.content,
            port = obj.getValue("port").jsonPrimitive.intOrNull ?: 22,
            user = obj.getValue("user").jsonPrimitive.content,
        ),
        auth = AuthMethod.valueOf(obj.getValue("auth").jsonPrimitive.content),
    )

    private companion object {
        const val PREFS = "whistl.machines"
        const val KEY = "machines"
    }
}

/**
 * Settings in private SharedPreferences: plain string values, which the settings repository encodes.
 *
 * @param context any context; the application context is used.
 */
class SharedPreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private companion object {
        const val PREFS = "whistl.settings"
    }
}

/**
 * Pinned host keys in private SharedPreferences: `algorithm|base64(blob)` per machine. A pin is public data.
 *
 * @param context any context; the application context is used.
 */
class SharedPreferencesHostKeyStore(context: Context) : HostKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun pinned(machineId: String): PinnedHostKey? {
        val text = prefs.getString(key(machineId), null) ?: return null
        val separator = text.indexOf('|')
        if (separator < 0) return null
        return PinnedHostKey(text.substring(0, separator), Base64.getDecoder().decode(text.substring(separator + 1)))
    }

    override fun pin(machineId: String, key: PinnedHostKey) {
        val encoded = key.algorithm + "|" + Base64.getEncoder().encodeToString(key.blob)
        prefs.edit().putString(key(machineId), encoded).apply()
    }

    override fun forget(machineId: String) {
        prefs.edit().remove(key(machineId)).apply()
    }

    private fun key(machineId: String): String = "pin.$machineId"

    private companion object {
        const val PREFS = "whistl.hostkeys"
    }
}

/**
 * The last `whistl` document per machine, as JSON text in private SharedPreferences. Read back through
 * [SpacesDocument.fromJson]; a copy that no longer decodes counts as absent.
 *
 * @param context any context; the application context is used.
 */
class SharedPreferencesSpacesCache(context: Context) : SpacesCache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(machineId: String): SpacesDocument? {
        val text = prefs.getString(key(machineId), null) ?: return null
        return try {
            SpacesDocument.fromJson(Json.parseToJsonElement(text))
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun put(machineId: String, document: SpacesDocument) {
        val text = Json.encodeToString(JsonObject.serializer(), document.toJson())
        prefs.edit().putString(key(machineId), text).apply()
    }

    private fun key(machineId: String): String = "spaces.$machineId"

    private companion object {
        const val PREFS = "whistl.spaces"
    }
}
