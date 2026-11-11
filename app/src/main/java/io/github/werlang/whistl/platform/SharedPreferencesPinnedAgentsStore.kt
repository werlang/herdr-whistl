package io.github.werlang.whistl.platform

import android.content.Context
import io.github.werlang.whistl.data.PinnedAgentsStore

/**
 * The pinned agents in this app's preferences: a set of `machine/address` strings, never a secret.
 *
 * @param context any context; the application context is kept.
 */
class SharedPreferencesPinnedAgentsStore(context: Context) : PinnedAgentsStore {
    private val prefs = context.applicationContext.getSharedPreferences("whistl-pinned-agents", Context.MODE_PRIVATE)

    override fun read(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    override fun write(keys: Set<String>) {
        prefs.edit().putStringSet(KEY, keys).apply()
    }

    private companion object {
        const val KEY = "pinned"
    }
}
