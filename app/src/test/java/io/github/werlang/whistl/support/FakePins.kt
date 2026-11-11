package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.PinnedAgents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pinned agents kept in memory: the set the test gives, and every toggle applied to it.
 *
 * @param initial the keys pinned at the start.
 */
class FakePins(initial: Set<String> = emptySet()) : PinnedAgents {
    private val state = MutableStateFlow(initial)

    override val pinned: StateFlow<Set<String>> = state

    /** How many times a key was toggled. */
    var toggles: Int = 0

    override fun toggle(key: String) {
        toggles++
        state.value = if (key in state.value) state.value - key else state.value + key
    }
}
