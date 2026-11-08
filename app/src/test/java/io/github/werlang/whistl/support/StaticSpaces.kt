package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceResult
import io.github.werlang.whistl.data.SpacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spaces that never change: the groups the test gives. Writes answer [SpaceResult.MachineOffline]; the tests that write use the
 * real repository through [RepoStack].
 *
 * @param initial the groups.
 */
class StaticSpaces(initial: List<MachineSpaces>) : SpacesRepository {
    private val groupsFlow = MutableStateFlow(initial)

    /** Replaces the groups the repository reports. */
    fun set(groups: List<MachineSpaces>) {
        groupsFlow.value = groups
    }

    override fun groups(): StateFlow<List<MachineSpaces>> = groupsFlow

    override suspend fun create(machineId: String, folder: String, name: String): SpaceResult = SpaceResult.MachineOffline

    override suspend fun rename(machineId: String, spaceId: String, name: String): SpaceResult = SpaceResult.MachineOffline

    override suspend fun setPinned(machineId: String, spaceId: String, pinned: Boolean): SpaceResult = SpaceResult.MachineOffline

    override suspend fun moveUp(machineId: String, spaceId: String): SpaceResult = SpaceResult.MachineOffline

    override suspend fun moveDown(machineId: String, spaceId: String): SpaceResult = SpaceResult.MachineOffline

    override suspend fun close(machineId: String, spaceId: String): SpaceResult = SpaceResult.MachineOffline
}
