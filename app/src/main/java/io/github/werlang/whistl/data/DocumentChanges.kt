package io.github.werlang.whistl.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries "the `whistl` document on this machine changed" from the events channel to the spaces repository.
 *
 * It is a hub rather than a call, so the agents and spaces repositories do not depend on each other in a cycle: agents
 * publish changes here, spaces listen. A change with no listener is dropped, which is fine: a listener re-reads the
 * document when it starts.
 */
class DocumentChanges {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    /** The machine ids whose `whistl` document changed, one emission per `doc` event. */
    val changes: SharedFlow<String> = events.asSharedFlow()

    /**
     * Announces that the `whistl` document on [machineId] changed.
     *
     * @param machineId the machine id.
     */
    fun notifyChanged(machineId: String) {
        events.tryEmit(machineId)
    }

    private companion object {
        const val BUFFER = 64
    }
}
