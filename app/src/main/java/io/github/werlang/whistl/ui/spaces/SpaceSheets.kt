package io.github.werlang.whistl.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.machines.text
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.machines.SheetRow
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the New space sheet. */
const val NEW_SPACE_SHEET_TAG: String = "new-space-sheet"

/** The test tag of the New space folder field. */
const val NEW_SPACE_FOLDER_TAG: String = "new-space-folder"

/** The test tag of the New space name field. */
const val NEW_SPACE_NAME_TAG: String = "new-space-name"

/** The test tag of the New space Create button. */
const val NEW_SPACE_CREATE_TAG: String = "new-space-create"

/** The test tag of the New space sheet's Add machine button, shown when no machine is added. */
const val NEW_SPACE_ADD_MACHINE_TAG: String = "new-space-add-machine"

/** The test tag of the row menu sheet. */
const val SPACE_MENU_SHEET_TAG: String = "space-menu-sheet"

/** The test tag of the menu's Pin or Unpin row. */
const val MENU_SPACE_PIN_TAG: String = "menu-space-pin"

/** The test tag of the menu's Move up row. */
const val MENU_SPACE_MOVE_UP_TAG: String = "menu-space-move-up"

/** The test tag of the menu's Move down row. */
const val MENU_SPACE_MOVE_DOWN_TAG: String = "menu-space-move-down"

/** The test tag of the menu's Rename row. */
const val MENU_SPACE_RENAME_TAG: String = "menu-space-rename"

/** The test tag of the menu's Close space row. */
const val MENU_SPACE_CLOSE_TAG: String = "menu-space-close"

/** The test tag of the rename sheet. */
const val RENAME_SPACE_SHEET_TAG: String = "rename-space-sheet"

/** The test tag of the rename field. */
const val RENAME_SPACE_FIELD_TAG: String = "rename-space-field"

/** The test tag of the rename sheet's Save button. */
const val RENAME_SPACE_SAVE_TAG: String = "rename-space-save"

/** The test tag of the close confirmation sheet. */
const val CLOSE_SPACE_SHEET_TAG: String = "close-space-sheet"

/** The test tag of the close confirmation's Close button. */
const val CLOSE_SPACE_CONFIRM_TAG: String = "close-space-confirm"

/**
 * The test tag of one machine's choice in the New space sheet: `new-space-machine-<id>`.
 *
 * @param id the machine.
 * @return the tag.
 */
fun newSpaceMachineTag(id: String): String = "new-space-machine-$id" // i18n-ok: test tag, never shown

/**
 * The New space sheet (SPEC §2.2): pick an added machine, a folder (default `~`), and a name (default: the folder's last
 * segment, shown as the placeholder). Problems show under their field. With no machine added, it says so and offers Add machine.
 *
 * @param ui the sheet's state.
 * @param onPick called with the machine the user picked.
 * @param onFolder called when the folder text changes.
 * @param onName called when the name text changes.
 * @param onCreate called to create the space.
 * @param onAddMachine called to go to Machines, when no machine is added.
 * @param onDismiss called when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSpaceSheet(
    ui: NewSpaceUi,
    onPick: (String) -> Unit,
    onFolder: (String) -> Unit,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onAddMachine: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(NEW_SPACE_SHEET_TAG)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.space_new_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (ui.machines.isEmpty()) {
                Text(
                    text = stringResource(R.string.spaces_no_machine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onAddMachine,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_SPACE_ADD_MACHINE_TAG),
                    ) {
                        Text(stringResource(R.string.add_machine_title))
                    }
                }
                return@ModalBottomSheet
            }
            Text(
                text = stringResource(R.string.space_field_machine),
                style = MaterialTheme.typography.labelLarge,
                color = LocalWhistlTokens.current.muted,
            )
            ui.machines.forEach { machine ->
                val selected = machine.id == ui.selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .testTag(newSpaceMachineTag(machine.id))
                        .clickable(role = Role.RadioButton, onClick = { onPick(machine.id) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = machine.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = machine.address.text(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = WhistlMono,
                            color = LocalWhistlTokens.current.muted,
                        )
                    }
                    if (machine.offline) {
                        Text(
                            text = stringResource(R.string.machine_status_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalWhistlTokens.current.muted,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = ui.folder,
                onValueChange = onFolder,
                label = { Text(stringResource(R.string.space_field_folder)) },
                placeholder = { Text(stringResource(R.string.space_folder_placeholder)) },
                singleLine = true,
                isError = ui.folderProblem != null,
                supportingText = problemText(ui.folderProblem),
                modifier = Modifier.fillMaxWidth().testTag(NEW_SPACE_FOLDER_TAG),
            )
            OutlinedTextField(
                value = ui.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.space_field_name)) },
                placeholder = { Text(ui.defaultName) },
                singleLine = true,
                isError = ui.nameProblem != null,
                supportingText = problemText(ui.nameProblem),
                modifier = Modifier.fillMaxWidth().testTag(NEW_SPACE_NAME_TAG),
            )
            if (ui.machineOffline) {
                Text(
                    text = stringResource(R.string.space_machine_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onCreate,
                    enabled = ui.selectedId != null,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(NEW_SPACE_CREATE_TAG),
                ) {
                    Text(stringResource(R.string.space_create))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The row menu (SPEC §2.2): Pin or Unpin, Move up, Move down, Rename, and Close space. Move up and Move down are off at the edge of
 * the pin section; every action is off on an offline machine.
 *
 * @param ref the space.
 * @param onPin called to pin or unpin it.
 * @param onMoveUp called to move it up.
 * @param onMoveDown called to move it down.
 * @param onRename called to rename it.
 * @param onClose called to close it.
 * @param onDismiss called when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceMenuSheet(
    ref: SpaceRef,
    onPin: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(SPACE_MENU_SHEET_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(text = ref.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (ref.offline) {
                Text(
                    text = stringResource(R.string.space_menu_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalWhistlTokens.current.muted,
                )
            }
            Spacer(Modifier.size(8.dp))
            SheetRow(
                text = stringResource(if (ref.pinned) R.string.space_menu_unpin else R.string.space_menu_pin),
                enabled = !ref.offline,
                modifier = Modifier.testTag(MENU_SPACE_PIN_TAG),
                onClick = onPin,
            )
            SheetRow(
                text = stringResource(R.string.space_menu_move_up),
                enabled = !ref.offline && ref.canMoveUp,
                modifier = Modifier.testTag(MENU_SPACE_MOVE_UP_TAG),
                onClick = onMoveUp,
            )
            SheetRow(
                text = stringResource(R.string.space_menu_move_down),
                enabled = !ref.offline && ref.canMoveDown,
                modifier = Modifier.testTag(MENU_SPACE_MOVE_DOWN_TAG),
                onClick = onMoveDown,
            )
            SheetRow(
                text = stringResource(R.string.space_menu_rename),
                enabled = !ref.offline,
                modifier = Modifier.testTag(MENU_SPACE_RENAME_TAG),
                onClick = onRename,
            )
            SheetRow(
                text = stringResource(R.string.space_menu_close),
                enabled = !ref.offline,
                danger = true,
                modifier = Modifier.testTag(MENU_SPACE_CLOSE_TAG),
                onClick = onClose,
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The rename sheet: a name field, validated when saved. A rejected name shows under the field and nothing is written.
 *
 * @param ui the sheet's state.
 * @param onDraft called when the name text changes.
 * @param onSave called to save the name.
 * @param onCancel called to close without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameSpaceSheet(
    ui: RenameUi,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel, modifier = Modifier.testTag(RENAME_SPACE_SHEET_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.space_rename_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = ui.draft,
                onValueChange = onDraft,
                label = { Text(stringResource(R.string.space_field_name)) },
                singleLine = true,
                isError = ui.problem != null,
                supportingText = problemText(ui.problem),
                modifier = Modifier.fillMaxWidth().testTag(RENAME_SPACE_FIELD_TAG),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSave,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(RENAME_SPACE_SAVE_TAG),
                ) {
                    Text(stringResource(R.string.space_rename_save))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The close confirmation (SPEC §2.2): the space's name, and how many of its agents will be stopped. Its agents are stopped by
 * the repository before the space leaves the document.
 *
 * @param ui the space and its live agent count.
 * @param onConfirm called to close the space.
 * @param onCancel called to keep it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseSpaceSheet(
    ui: CloseUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel, modifier = Modifier.testTag(CLOSE_SPACE_SHEET_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.space_close_title, ui.target.name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (ui.agentCount > 0) {
                    pluralStringResource(R.plurals.space_close_agents, ui.agentCount, ui.agentCount)
                } else {
                    stringResource(R.string.space_close_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LocalWhistlTokens.current.muted,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CLOSE_SPACE_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.space_menu_close))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The message of a form problem.
 *
 * @param problem the problem.
 * @return the string resource.
 */
fun problemRes(problem: SpaceProblem): Int = when (problem) {
    SpaceProblem.FolderEmpty -> R.string.problem_space_folder_empty
    SpaceProblem.FolderBad -> R.string.problem_space_folder_bad
    SpaceProblem.NameEmpty -> R.string.problem_name_empty
    SpaceProblem.NameTooLong -> R.string.problem_name_too_long
}

/**
 * The message under a field, for its problem, in the error colour.
 *
 * @param problem the problem, or `null` for none.
 * @return the message, or `null`.
 */
@Composable
private fun problemText(problem: SpaceProblem?): (@Composable () -> Unit)? {
    if (problem == null) return null
    val message = stringResource(problemRes(problem))
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}
