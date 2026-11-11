package io.github.werlang.whistl.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the pinned agents are kept: a small set of keys, on this device only (phase 9, SPEC §2.4).
 */
interface PinnedAgentsStore {
    /** @return the keys that were saved. */
    fun read(): Set<String>

    /** @param keys the keys to save. */
    fun write(keys: Set<String>)
}

/**
 * The agents the user pinned. A pin does not touch the core: it is a preference of this device, and it only decides the
 * order the list shows — pinned agents first, then the screen's own order.
 */
interface PinnedAgents {
    /** The pinned agent keys, as [key] builds them. */
    val pinned: StateFlow<Set<String>>

    /**
     * Pins an unpinned agent, or unpins a pinned one.
     *
     * @param key the agent's key.
     */
    fun toggle(key: String)

    companion object {
        /**
         * @param machineId the machine the agent runs on.
         * @param address the terminal address.
         * @return the key the store holds.
         */
        fun key(machineId: String, address: String): String = "$machineId/$address"
    }
}

/**
 * [PinnedAgents] over a store.
 *
 * @param store where the keys are kept.
 */
class DefaultPinnedAgents(private val store: PinnedAgentsStore) : PinnedAgents {
    private val state = MutableStateFlow(store.read())

    override val pinned: StateFlow<Set<String>> = state.asStateFlow()

    override fun toggle(key: String) {
        val next = if (key in state.value) state.value - key else state.value + key
        state.value = next
        store.write(next)
    }
}
