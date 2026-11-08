package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.ResolvedFolderCache
import io.github.werlang.whistl.data.SpacesCache
import io.github.werlang.whistl.data.SpacesDocument

/** In-memory [SpacesCache] for tests. */
class InMemorySpacesCache : SpacesCache {
    private val docs = mutableMapOf<String, SpacesDocument>()

    override fun get(machineId: String): SpacesDocument? = docs[machineId]

    override fun put(machineId: String, document: SpacesDocument) {
        docs[machineId] = document
    }

    override fun remove(machineId: String) {
        docs.remove(machineId)
    }
}

/** In-memory [ResolvedFolderCache] for tests. */
class InMemoryResolvedFolderCache : ResolvedFolderCache {
    private val paths = mutableMapOf<Pair<String, String>, String>()

    override fun get(machineId: String, folder: String): String? = paths[machineId to folder]

    override fun put(machineId: String, folder: String, path: String) {
        paths[machineId to folder] = path
    }

    override fun remove(machineId: String) {
        paths.keys.removeAll { it.first == machineId }
    }
}
