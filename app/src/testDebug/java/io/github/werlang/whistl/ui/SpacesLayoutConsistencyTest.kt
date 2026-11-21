package io.github.werlang.whistl.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.ui.machines.MachineAddress
import io.github.werlang.whistl.ui.machines.MachineCard
import io.github.werlang.whistl.ui.machines.MachineStatus
import io.github.werlang.whistl.ui.machines.MachinesScreen
import io.github.werlang.whistl.ui.machines.MachinesUiState
import io.github.werlang.whistl.ui.machines.machineCardTag
import io.github.werlang.whistl.ui.spaces.FolderState
import io.github.werlang.whistl.ui.spaces.SpaceGroup
import io.github.werlang.whistl.ui.spaces.SpaceRow
import io.github.werlang.whistl.ui.spaces.SpacesScreen
import io.github.werlang.whistl.ui.spaces.SpacesUiState
import io.github.werlang.whistl.ui.spaces.spaceRowTag
import io.github.werlang.whistl.ui.theme.WhistlTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Consistency between Machines and Spaces card lists (SPEC §3): cards within a Spaces group keep the same 10 dp vertical gap
 * as Machines cards, instead of stacking flush inside a single LazyColumn group item.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class SpacesLayoutConsistencyTest : ScreenHarness() {

    /** Consecutive machine cards are separated by the 10 dp card list rhythm (SPEC §3). */
    @Test
    fun machinesCardsUseTenDpVerticalGap() {
        val machinesState = MachinesUiState(
            cards = listOf(
                MachineCard(
                    id = "m1",
                    name = "rpi5",
                    address = MachineAddress(user = "root", host = "rpi5", port = 22),
                    status = MachineStatus.Connected,
                    issue = null,
                    agentCount = 0,
                    version = "0.1.19",
                    installed = null,
                    reconnectEnabled = true,
                ),
                MachineCard(
                    id = "m2",
                    name = "mac",
                    address = MachineAddress(user = "pablowerlang", host = "mac", port = 22),
                    status = MachineStatus.Connected,
                    issue = null,
                    agentCount = 1,
                    version = "0.1.21",
                    installed = null,
                    reconnectEnabled = true,
                ),
            ),
            menu = null,
            confirmRemove = null,
            hostKey = null,
            addSession = null,
        )
        composeRule.setContent {
            WhistlTheme(darkTheme = true) {
                MachinesScreen(state = machinesState, onMenu = {})
            }
        }
        composeRule.waitForIdle()
        val m1 = composeRule.onNodeWithTag(machineCardTag("m1")).getUnclippedBoundsInRoot()
        val m2 = composeRule.onNodeWithTag(machineCardTag("m2")).getUnclippedBoundsInRoot()
        assertEquals(CARD_LIST_GAP_DP, (m2.top - m1.bottom).value, 0.5f)
    }

    /** Consecutive space cards in the same machine group keep the same 10 dp vertical gap as Machines cards (SPEC §3). */
    @Test
    fun spacesCardsInSameGroupUseTenDpVerticalGap() {
        val spacesState = SpacesUiState(
            groups = listOf(
                SpaceGroup(
                    machineId = "mac",
                    name = "mac",
                    address = MachineAddress(user = "pablowerlang", host = "mac", port = 22),
                    offline = false,
                    machineStatus = MachineStatus.Connected,
                    rows = listOf(
                        SpaceRow(
                            machineId = "mac",
                            spaceId = "sp1",
                            name = "workspaces",
                            folder = "/Users/pablowerlang/workspaces",
                            pinned = false,
                            agents = emptyList(),
                            waitingCount = 0,
                            folderState = FolderState.Resolved,
                            dimmed = false,
                            canMoveUp = false,
                            canMoveDown = true,
                        ),
                        SpaceRow(
                            machineId = "mac",
                            spaceId = "sp2",
                            name = "node-aec",
                            folder = "/Users/pablowerlang/node-aec",
                            pinned = false,
                            agents = emptyList(),
                            waitingCount = 0,
                            folderState = FolderState.Resolved,
                            dimmed = false,
                            canMoveUp = true,
                            canMoveDown = false,
                        ),
                    ),
                ),
            ),
            isEmpty = false,
            hasMachines = true,
            hasConnectedMachine = true,
            newSpace = null,
            menu = null,
            rename = null,
            close = null,
        )
        composeRule.setContent {
            WhistlTheme(darkTheme = true) {
                SpacesScreen(state = spacesState, onOpen = { _, _ -> }, onMenu = { _, _ -> })
            }
        }
        composeRule.waitForIdle()
        val s1 = composeRule.onNodeWithTag(spaceRowTag("mac", "sp1")).getUnclippedBoundsInRoot()
        val s2 = composeRule.onNodeWithTag(spaceRowTag("mac", "sp2")).getUnclippedBoundsInRoot()
        assertEquals(CARD_LIST_GAP_DP, (s2.top - s1.bottom).value, 0.5f)
    }

    private companion object {
        val CARD_LIST_GAP_DP = 10.dp.value
    }
}
