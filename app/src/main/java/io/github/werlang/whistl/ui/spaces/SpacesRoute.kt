package io.github.werlang.whistl.ui.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * The Spaces destination: the list, the row menu, the rename and close sheets, and the New space sheet while it is open.
 *
 * @param model the Spaces view model, owned by the app so the add button can open the New space sheet.
 * @param onOpenSpace called with the machine and the space when a row is tapped: the Space detail opens.
 * @param onAddMachine called when New space has no machine to use: the Machines destination opens.
 * @param modifier the modifier for the screen.
 */
@Composable
fun SpacesRoute(
    model: SpacesViewModel,
    onOpenSpace: (machineId: String, spaceId: String) -> Unit,
    onOpenSpaceDetail: (machineId: String, spaceId: String) -> Unit = { _, _ -> },
    onAddMachine: () -> Unit,
    onClearFilter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    SpacesScreen(
        state = state,
        onOpen = onOpenSpace,
        onMenu = { machineId, spaceId -> model.openMenu(machineId, spaceId) },
        onClearFilter = onClearFilter,
        modifier = modifier,
    )
    state.folderPicker?.let { picker ->
        FolderPickerSheet(
            ui = picker,
            onUp = model::folderUp,
            onEnter = model::folderDown,
            onUse = model::useFolder,
            onDismiss = model::closeFolderPicker,
        )
    }
    // One sheet at a time: while the picker is open it stands in front of the sheet it fills.
    state.newSpace?.takeIf { state.folderPicker == null }?.let { ui ->
        NewSpaceSheet(
            ui = ui,
            onPick = model::pickMachine,
            onFolder = model::editFolder,
            onName = model::editName,
            onCreate = model::createSpace,
            onBrowse = model::browseFolder,
            onAddMachine = {
                model.closeNewSpace()
                onAddMachine()
            },
            onDismiss = model::closeNewSpace,
        )
    }
    state.menu?.let { ref ->
        SpaceMenuSheet(
            ref = ref,
            onPin = model::togglePin,
            onMoveUp = model::moveUp,
            onMoveDown = model::moveDown,
            onRename = model::askRename,
            onClose = model::askClose,
            onNewAgent = {
                val opened = ref
                model.closeMenu()
                onOpenSpaceDetail(opened.machineId, opened.spaceId)
            },
            onDismiss = model::closeMenu,
        )
    }
    state.rename?.let { ui ->
        RenameSpaceSheet(ui = ui, onDraft = model::editRename, onSave = model::confirmRename, onCancel = model::cancelRename)
    }
    state.close?.let { ui ->
        CloseSpaceSheet(ui = ui, onConfirm = model::confirmClose, onCancel = model::cancelClose)
    }
}
