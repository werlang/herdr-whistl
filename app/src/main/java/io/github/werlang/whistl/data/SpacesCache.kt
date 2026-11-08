package io.github.werlang.whistl.data

import io.github.werlang.whistl.data.SpacesDocument

/**
 * The last `whistl` document read from each machine (SPEC §1: an offline machine's spaces render from this copy).
 * Tests use an in-memory fake; the app uses a SharedPreferences implementation in `platform/`.
 */
interface SpacesCache {
    /**
     * The cached document for a machine.
     *
     * @param machineId the machine id.
     * @return the document, or `null` when none was ever cached for it.
     */
    fun get(machineId: String): SpacesDocument?

    /**
     * Stores the document for a machine, replacing the old copy.
     *
     * @param machineId the machine id.
     * @param document the document as last read or written.
     */
    fun put(machineId: String, document: SpacesDocument)
}
