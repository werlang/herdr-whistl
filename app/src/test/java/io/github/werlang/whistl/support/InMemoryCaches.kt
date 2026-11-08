package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.SpacesCache
import io.github.werlang.whistl.data.SpacesDocument

/** In-memory [SpacesCache] for tests. */
class InMemorySpacesCache : SpacesCache {
    private val docs = mutableMapOf<String, SpacesDocument>()

    override fun get(machineId: String): SpacesDocument? = docs[machineId]

    override fun put(machineId: String, document: SpacesDocument) {
        docs[machineId] = document
    }
}
