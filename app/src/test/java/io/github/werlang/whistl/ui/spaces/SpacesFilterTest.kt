package io.github.werlang.whistl.ui.spaces

import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The machine filter of the Spaces screen (phase 9, SPEC §2.2): tapping a machine shows only its spaces, until the filter
 * is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpacesFilterTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val rpi = Machine(id = RPI, name = "rpi5", host = "rpi5.lan", port = 22, user = "ci")
    private val mac = Machine(id = MAC, name = "mac", host = "mac.lan", port = 22, user = "ci")

    private fun spaces() = StaticSpaces(
        listOf(
            MachineSpaces(RPI, offline = false, spaces = listOf(SpaceRecord(id = "sp_rpi", name = "web", folder = "~/web", pinned = false, order = 0))),
            MachineSpaces(MAC, offline = false, spaces = listOf(SpaceRecord(id = "sp_mac", name = "docs", folder = "~/docs", pinned = false, order = 1))),
        ),
    )

    private fun vm(filter: MutableStateFlow<String?>) = SpacesViewModel(
        spaces(),
        StaticAgents(),
        ScriptedMachines().apply {
            put(rpi, ConnectionState.Offline)
            put(mac, ConnectionState.Offline)
        },
        filter,
    )

    /** No filter shows both machines' spaces, and says so by having nothing to announce. */
    @Test
    fun withoutAFilterEveryMachineIsShown() = runTest {
        val model = vm(MutableStateFlow(null))

        val state = model.state.value
        assertEquals(listOf("rpi5", "mac"), state.groups.map { it.name })
        assertNull(state.filterName)
    }

    /** A filter keeps only that machine's group and names it, so the screen can offer the way out. */
    @Test
    fun aFilterShowsOnlyThatMachine() = runTest {
        val model = vm(MutableStateFlow(RPI))

        val state = model.state.value
        assertEquals(listOf("rpi5"), state.groups.map { it.name })
        assertEquals("rpi5", state.filterName)
    }

    /** Clearing the filter brings the other machine back: the VM follows the flow, not a snapshot. */
    @Test
    fun clearingTheFilterBringsTheOtherMachineBack() = runTest {
        val filter = MutableStateFlow<String?>(RPI)
        val model = vm(filter)
        assertEquals(1, model.state.value.groups.size)

        filter.value = null

        assertEquals(2, model.state.value.groups.size)
        assertNull(model.state.value.filterName)
    }

    /** An empty list under a filter still says which machine is being shown. */
    @Test
    fun aMachineWithoutSpacesStillNamesTheFilter() = runTest {
        val model = vm(MutableStateFlow("unknown-machine"))

        val state = model.state.value
        assertEquals(emptyList<SpaceGroup>(), state.groups)
        assertEquals("unknown-machine", state.filterName)
    }

    private companion object {
        const val RPI = "rpi5"
        const val MAC = "mac"
    }
}
