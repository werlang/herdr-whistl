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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.FolderProblem
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.components.whistlButtonColors
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/** The test tag of the folder picker sheet. */
const val FOLDER_PICKER_TAG: String = "folder-picker"

/** The test tag of its "use this folder" button. */
const val FOLDER_PICKER_USE_TAG: String = "folder-picker-use"

/** The test tag of its "up" row. */
const val FOLDER_PICKER_UP_TAG: String = "folder-picker-up"

/**
 * Browsing a machine's folders to choose a space's folder (phase 9, SPEC 2.3): the path, the way up, the folders inside,
 * and one button that takes the folder being shown. Tapping a folder goes into it.
 *
 * @param ui what the picker shows.
 * @param onUp goes to the parent folder.
 * @param onEnter goes into a folder.
 * @param onUse takes the folder being shown.
 * @param onDismiss closes the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    ui: FolderPickerUi,
    onUp: () -> Unit,
    onEnter: (String) -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalWhistlTokens.current
    ModalBottomSheet(
        dragHandle = { SheetDragHandle() },
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(FOLDER_PICKER_TAG),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.space_folder_browse_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = ui.path.ifEmpty { stringResource(R.string.space_folder_browse_loading) },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = tokens.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp))
            }
            ui.problem?.let { problem ->
                Text(
                    text = stringResource(
                        when (problem) {
                            FolderProblem.Offline -> R.string.space_folder_browse_offline
                            FolderProblem.Unreadable -> R.string.space_folder_browse_unreadable
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                if (ui.parent != null) {
                    item {
                        FolderRow(
                            label = "..", // i18n-ok: the parent folder's name in every locale
                            description = stringResource(R.string.space_folder_browse_up),
                            tag = FOLDER_PICKER_UP_TAG,
                            onClick = onUp,
                        )
                    }
                }
                items(ui.folders) { name ->
                    FolderRow(label = "$name/", description = null, tag = null, onClick = { onEnter(name) }) // i18n-ok: a folder's own name from the machine
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onUse,
                    enabled = !ui.loading && ui.problem == null && ui.path.isNotEmpty(),
                    colors = whistlButtonColors(),
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(FOLDER_PICKER_USE_TAG),
                ) {
                    Text(stringResource(R.string.space_folder_browse_use))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FolderRow(label: String, description: String?, tag: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .let { base -> if (tag != null) base.testTag(tag) else base }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = description ?: label,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
