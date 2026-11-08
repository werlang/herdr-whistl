package io.github.werlang.whistl.core.rpc

import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState

/**
 * Applies events to a snapshot of terminals, keyed by address (SPEC §5).
 *
 * Handled: `labels` (replace the label map), `finished` (state becomes [TerminalState.Finished]) and
 * `removed` (the terminal leaves the map). Other kinds leave the map unchanged in this phase; the app
 * applies them when the screens need them.
 */
object TerminalReducer {
    /**
     * Returns the terminal map after one event. The input map is not changed.
     *
     * An event for a terminal not in [current] is ignored, except that a `labels` event for an unknown terminal
     * never adds it: the snapshot is the only source of new terminals.
     *
     * @param current the terminals before the event, keyed by address.
     * @param event the parsed event.
     * @return the terminals after the event.
     */
    fun apply(current: Map<String, Terminal>, event: HcEvent): Map<String, Terminal> = when (event) {
        is HcEvent.LabelsChanged -> current[event.terminal]
            ?.let { current + (event.terminal to it.copy(labels = event.labels)) }
            ?: current

        is HcEvent.TerminalChanged -> when (event.kind) {
            TerminalEventKind.Finished -> current[event.terminal]
                ?.let { current + (event.terminal to it.copy(state = TerminalState.Finished)) }
                ?: current

            TerminalEventKind.Removed -> current - event.terminal

            else -> current
        }

        else -> current
    }
}
