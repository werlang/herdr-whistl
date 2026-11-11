package io.github.werlang.whistl.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
 * The action sheet of one agent (phase 9, SPEC §2.4): Interrupt, Close & Remove, Rename, Pin.
 *
 * The same sheet opens from the Agents list and from a space's agent rows, so the four actions are the same wherever an
 * agent is shown. Close & Remove asks once, in the sheet itself, because removing an agent cannot be undone.
 *
 * @param menu the row and, when renaming or confirming, the draft and the question.
 * @param onInterrupt asks the agent to stop what it is doing.
 * @param onAskClose turns the sheet into the close question.
 * @param onCancelClose goes back from the question.
 * @param onClose stops the agent and removes it.
 * @param onStartRename turns the sheet into the rename field.
 * @param onDraft receives every keystroke of the new name.
 * @param onConfirmRename saves the typed name.
 * @param onTogglePin pins the agent, or unpins it.
 * @param onDismiss closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMenuSheet(
    menu: AgentMenu,
    onInterrupt: () -> Unit,
    onAskClose: () -> Unit,
    onCancelClose: () -> Unit,
    onClose: () -> Unit,
    onStartRename: () -> Unit,
    onDraft: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onTogglePin: () -> Unit,
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
            when {
                menu.confirmingClose -> {
                    Text(
                        text = stringResource(R.string.agents_close_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.agents_action_close), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancelClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_cancel), modifier = Modifier.fillMaxWidth())
                    }
                }
                menu.renaming -> {
                    OutlinedTextField(
                        value = menu.draft,
                        onValueChange = onDraft,
                        singleLine = true,
                        label = { Text(stringResource(R.string.agents_rename_label)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp),
                    )
                    TextButton(
                        onClick = onConfirmRename,
                        enabled = menu.draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.agents_rename_save))
                    }
                }
                else -> {
                    // A finished agent has nothing to interrupt; the other three always make sense.
                    if (menu.row.live) {
                        SheetAction(stringResource(R.string.agents_action_interrupt), onInterrupt)
                    }
                    SheetAction(stringResource(R.string.agents_action_close), onAskClose)
                    SheetAction(stringResource(R.string.agents_action_rename), onStartRename)
                    SheetAction(
                        stringResource(if (menu.row.pinned) R.string.agents_action_unpin else R.string.agents_action_pin),
                        onTogglePin,
                    )
                }
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
