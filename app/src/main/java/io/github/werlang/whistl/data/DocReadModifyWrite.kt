package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.Conflict
import io.github.werlang.whistl.core.rpc.HcClient

/**
 * Reads the `whistl` document, applies [transform], and writes it back with compare-and-set (SPEC §5).
 *
 * Each attempt is: `doc.get` (keeps the version), [transform], `doc.set` with that version. A [Conflict] means another
 * writer changed the document since the read, so the loop reads again and tries again. The number of conflicts
 * tolerated is bounded by [maxAttempts]. Any other error, such as `not_found`, propagates unchanged on its first
 * occurrence.
 *
 * [transform] may run more than once, once per attempt. It must be a pure function of its argument.
 *
 * @param client the typed hc client.
 * @param name the document name on the core (the fixtures use `whistl`).
 * @param maxAttempts the number of write attempts allowed; the last [Conflict] is rethrown. Must be at least 1.
 * @param transform the change to apply to the document read.
 * @return the document as written.
 * @throws Conflict after [maxAttempts] conflicting writes.
 * @throws IllegalArgumentException when [maxAttempts] is below 1.
 */
suspend fun docReadModifyWrite(
    client: HcClient,
    name: String,
    maxAttempts: Int,
    transform: (SpacesDocument) -> SpacesDocument,
): SpacesDocument {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    var conflicts = 0
    while (true) {
        val read = client.docGet(name)
        val next = transform(SpacesDocument.fromJson(read.value))
        try {
            client.docSet(name, next.toJson(), read.version)
            return next
        } catch (conflict: Conflict) {
            conflicts++
            if (conflicts >= maxAttempts) throw conflict
        }
    }
}
