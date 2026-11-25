package io.github.werlang.whistl.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithText
import io.github.werlang.whistl.data.ConnectionState
import io.github.werlang.whistl.data.MachineAgent
import io.github.werlang.whistl.data.MachineSpaces
import io.github.werlang.whistl.data.SpaceRecord
import io.github.werlang.whistl.domain.Agent
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.support.ScriptedMachines
import io.github.werlang.whistl.support.StaticAgents
import io.github.werlang.whistl.support.StaticSpaces
import io.github.werlang.whistl.support.testHello
import io.github.werlang.whistl.ui.space.NewAgentBlock
import io.github.werlang.whistl.ui.space.NewAgentUi
import io.github.werlang.whistl.ui.space.SpaceDetailScreen
import io.github.werlang.whistl.ui.space.SpaceDetailUiState
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.SpacesScreen
import io.github.werlang.whistl.ui.spaces.SpacesViewModel
import io.github.werlang.whistl.ui.theme.WhistlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Spaces card names (SPEC §2.2): a card names each of its agents, and a space the desktop keeps without a folder does not
 * show an invented "Folder not checked yet" line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class SpaceCardNamesTest : ScreenHarness() {

    @get:Rule
    val main = MainDispatcherRule()

    /** A terminal of the space, with the `where` the core composes and no explicit name. */
    private fun terminal(): Terminal = Terminal(
        address = "t16b",
        name = null,
        argv = listOf("claude"),
        cwd = null,
        labels = mapOf("herdr.space" to "sp9aed68", "where" to "gladcode2 / Claude Code"),
        state = TerminalState.Live,
        status = AgentStatus.Working,
        turn = 6,
        exitCode = null,
        createdMs = null,
        finishedMs = null,
        title = "Claude Code",
        pid = null,
        signal = null,
        reason = null,
        keep = false,
    )

    /** The card names the agent ("Claude Code") and keeps the blank folder from reading as a pending check. */
    @Test
    fun aSpaceCardShowsItsAgentNamesAndNoBlankFolderLine() {
        val space = SpaceRecord(id = "sp9aed68", name = "gladcode2", folder = "", pinned = false, order = 0)
        val machines = ScriptedMachines()
        machines.put(
            Machine(id = "rpi5", name = "rpi5", host = "rpi5", port = 22, user = "root"),
            ConnectionState.Connected(testHello()),
        )
        val vm = SpacesViewModel(
            StaticSpaces(listOf(MachineSpaces("rpi5", offline = false, spaces = listOf(space)))),
            StaticAgents(listOf(MachineAgent("rpi5", Agent(terminal()), space))),
            machines,
        )

        composeRule.setContent {
            val state by vm.state.collectAsState()
            WhistlTheme(darkTheme = true) {
                SpacesScreen(
                    state = state,
                    onOpen = { _, _ -> },
                    onMenu = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("gladcode2").assertExists()
        composeRule.onNodeWithText("Claude Code").assertExists()
        composeRule.onNodeWithText("Folder not checked yet").assertDoesNotExist()
    }

    /** The detail header of a space with no folder names the machine without a dangling separator. */
    @Test
    fun aSpaceDetailWithoutAFolderHasNoDanglingSeparator() {
        val state = SpaceDetailUiState(
            missing = false,
            name = "gladcode2",
            machineName = "rpi5",
            folder = "",
            folderState = FolderState.None,
            machineOffline = false,
            agents = emptyList(),
            newAgent = NewAgentUi(enabled = false, block = NewAgentBlock.Folder(FolderState.None)),
            picker = null,
            opened = null,
        )

        composeRule.setContent {
            WhistlTheme(darkTheme = true) {
                SpaceDetailScreen(
                    state = state,
                    onOpenAgent = {},
                    onNewAgent = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("rpi5").assertExists()
        composeRule.onNodeWithText("rpi5:").assertDoesNotExist()
        composeRule.onNodeWithText("Folder not checked yet").assertDoesNotExist()
    }
}
