package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.MachineStore
import io.github.werlang.whistl.data.SettingsStore
import io.github.werlang.whistl.data.StoredMachine

/** In-memory [MachineStore] for tests, in insertion order. */
class InMemoryMachineStore : MachineStore {
    private val items = LinkedHashMap<String, StoredMachine>()

    override fun list(): List<StoredMachine> = items.values.toList()

    override fun put(stored: StoredMachine) {
        items[stored.machine.id] = stored
    }

    override fun delete(id: String) {
        items.remove(id)
    }
}

/** In-memory [SettingsStore] for tests. */
class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}
