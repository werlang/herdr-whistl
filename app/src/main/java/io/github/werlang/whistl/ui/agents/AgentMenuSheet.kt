package io.github.werlang.whistl.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/** The test tag of the action sheet, so tests and screenshots can find it. */
const val AGENT_ACTIONS_SHEET_TAG: String = "agent-actions-sheet"

/**
 * The action sheet of one agent in the list (phase 9, SPEC §2.4): interrupt, stop, close, rename.
 *
 * The same sheet opens from the row's overflow button and from a long press. Closing and stopping are destructive, so
 * each says what it does with the agent; nothing is asked twice, because the sheet is already the confirmation of
 * intent.
 *
 * @param menu the row and, when renaming, the draft.
 * @param onInterrupt asks the agent to stop what it is doing.
 * @param onStop ends its process.
 * @param onClose stops it and removes it from the list.
 * @param onStartRename turns the sheet into the rename field.
 * @param onDraft receives every keystroke of the new name.
 * @param onConfirmRename saves the typed name.
 * @param onDismiss closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMenuSheet(
    menu: AgentMenu,
    onInterrupt: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onStartRename: () -> Unit,
    onDraft: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalWhistlTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(AGENT_ACTIONS_SHEET_TAG)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = menu.row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = menu.row.address,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.dim,
            )
            if (menu.renaming) {
                OutlinedTextField(
                    value = menu.draft,
                    onValueChange = onDraft,
                    singleLine = true,
                    label = { Text(stringResource(R.string.agents_rename_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                )
                TextButton(
                    onClick = onConfirmRename,
                    enabled = menu.draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.agents_rename_save))
                }
            } else {
                SheetAction(stringResource(R.string.agents_action_interrupt), onInterrupt)
                SheetAction(stringResource(R.string.agents_action_stop), onStop)
                SheetAction(stringResource(R.string.agents_action_close), onClose)
                SheetAction(stringResource(R.string.agents_action_rename), onStartRename)
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text = label, modifier = Modifier.fillMaxWidth())
    }
}
