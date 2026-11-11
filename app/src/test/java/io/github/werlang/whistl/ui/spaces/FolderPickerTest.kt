package io.github.werlang.whistl.ui.spaces

import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.FolderBrowser
import io.github.werlang.whistl.data.FolderListing
import io.github.werlang.whistl.data.FolderProblem
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The folder picker of the New space sheet (phase 9, SPEC 2.3): it opens at the machine's home, walks down and up, and
 * the folder it shows is what the new space gets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderPickerTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val machine = Machine(id = MACHINE, name = "rpi5", host = "rpi5.lan", port = 22, user = "ci")

    /** A browser the test scripts: a path to a listing, and everything else unreadable. */
    private class FakeBrowser(private val listings: Map<String, FolderListing>) : FolderBrowser {
        var asked = mutableListOf<String>()

        override suspend fun home(machineId: String): FolderListing {
            asked += "~"
            return listings.getValue("~")
        }

        override suspend fun list(machineId: String, path: String): FolderListing {
            asked += path
            return listings[path] ?: FolderListing(path, null, emptyList(), FolderProblem.Unreadable)
        }
    }

    private val listings = mapOf(
        "~" to FolderListing("/home/pablo", "/home", listOf("code", "docs")),
        "/home/pablo/code" to FolderListing("/home/pablo/code", "/home/pablo", listOf("whistl")),
        "/home" to FolderListing("/home", "/", listOf("pablo")),
    )

    private fun vm(browser: FolderBrowser = FakeBrowser(listings)) = SpacesViewModel(
        StaticSpaces(
            listOf(
                MachineSpaces(
                    MACHINE,
                    offline = false,
                    spaces = listOf(SpaceRecord(id = "sp_1", name = "web", folder = "~/web", pinned = false, order = 0)),
                ),
            ),
        ),
        StaticAgents(),
        ScriptedMachines().apply { put(machine, ConnectionState.Offline) },
        MutableStateFlow(null),
        browser,
    )

    /** Opening the picker reads the machine's home and shows its folders. */
    @Test
    fun thePickerOpensAtTheMachinesHome() = runTest {
        val model = vm()
        model.openNewSpace()

        model.browseFolder()
        runCurrent()

        val picker = model.state.value.folderPicker!!
        assertEquals("/home/pablo", picker.path)
        assertEquals(listOf("code", "docs"), picker.folders)
        assertNull(picker.problem)
    }

    /** Going down and up follows the folders; using one fills the sheet's folder field. */
    @Test
    fun walkingDownUpAndChoosingFillsTheFolder() = runTest {
        val model = vm()
        model.openNewSpace()
        model.browseFolder()
        runCurrent()

        model.folderDown("code")
        runCurrent()
        assertEquals("/home/pablo/code", model.state.value.folderPicker?.path)

        model.folderUp()
        runCurrent()
        assertEquals("/home/pablo", model.state.value.folderPicker?.path)

        model.folderDown("code")
        runCurrent()
        model.useFolder()

        assertNull("the picker closes on use", model.state.value.folderPicker)
        assertEquals("/home/pablo/code", model.state.value.newSpace?.folder)
    }

    /** A folder that cannot be read says so, and cannot be chosen. */
    @Test
    fun anUnreadableFolderIsNamedAndNotChosen() = runTest {
        val model = vm()
        model.openNewSpace()
        model.browseFolder()
        runCurrent()

        model.folderDown("gone")
        runCurrent()
        assertEquals(FolderProblem.Unreadable, model.state.value.folderPicker?.problem)

        val before = model.state.value.newSpace?.folder
        model.useFolder()

        assertEquals("/home/pablo/gone", model.state.value.folderPicker?.path)
        assertEquals("the folder is not taken", before, model.state.value.newSpace?.folder)
    }

    private companion object {
        const val MACHINE = "rpi5"
    }
}
