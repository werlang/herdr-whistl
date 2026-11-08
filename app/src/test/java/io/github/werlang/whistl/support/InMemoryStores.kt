package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.SettingsStore

/** In-memory [SettingsStore] for tests. */
class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}
