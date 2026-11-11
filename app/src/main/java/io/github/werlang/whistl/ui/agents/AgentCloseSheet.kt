package io.github.werlang.whistl.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.components.whistlButtonColors
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/** The test tag of the close confirmation sheet. */
const val AGENT_CLOSE_SHEET_TAG: String = "agent-close-sheet"

/** The test tag of its confirming button. */
const val AGENT_CLOSE_CONFIRM_TAG: String = "agent-close-confirm"

/**
 * Confirms closing an agent, in the shape the app uses everywhere for a destructive question (SPEC §3): a title naming
 * what is about to go, one line saying what happens, Cancel and an error-coloured button. Closing cannot be undone, so
 * it gets the same weight as removing a machine — not a second line inside the action sheet (0.5.1).
 *
 * @param name the agent's name.
 * @param onConfirm stops the agent and removes it.
 * @param onCancel goes back without doing anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCloseSheet(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        dragHandle = { SheetDragHandle() },
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(AGENT_CLOSE_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.agents_close_title, name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.agents_close_text),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalWhistlTokens.current.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = onConfirm,
                    colors = whistlButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(AGENT_CLOSE_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.agents_action_close))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}
