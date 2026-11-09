package io.github.werlang.whistl.ui.machines

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.whistlButtonColors
import io.github.werlang.whistl.ui.components.SheetDragHandle
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the card menu sheet. */
const val MACHINE_MENU_SHEET_TAG: String = "machine-menu-sheet"

/** The test tag of the remove confirmation sheet. */
const val REMOVE_MACHINE_SHEET_TAG: String = "remove-machine-sheet"

/** The test tag of the host-key sheet opened from a card. */
const val HOST_KEY_SHEET_TAG: String = "host-key-sheet"

/** The test tag of the Trust and connect button in the host-key sheet. */
const val HOST_KEY_TRUST_TAG: String = "host-key-trust"

/** The test tag of the Remove machine button in the changed-key sheet. */
const val HOST_KEY_REMOVE_TAG: String = "host-key-remove"

/** The test tag of the Reconnect row of the card menu. */
const val MENU_RECONNECT_TAG: String = "menu-reconnect"

/** The test tag of the Remove machine row of the card menu. */
const val MENU_REMOVE_TAG: String = "menu-remove"

/** The test tag of the Edit machine row of the card menu. */
const val MENU_EDIT_TAG: String = "menu-edit"

/**
 * The card menu (SPEC §2.1): Edit machine (phase 7 P2), Reconnect, which is off while the machine is connecting, and Remove
 * machine, which asks first.
 *
 * @param menu the machine.
 * @param onEdit called to open the Edit machine sheet.
 * @param onReconnect called to restart the connection.
 * @param onRemove called to ask for the removal.
 * @param onDismiss called when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineMenuSheet(
    menu: MachineMenu,
    onEdit: () -> Unit = {},
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = onDismiss, modifier = Modifier.testTag(MACHINE_MENU_SHEET_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(text = menu.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = menu.address.text(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = WhistlMono,
                color = LocalWhistlTokens.current.muted,
            )
            Spacer(Modifier.size(8.dp))
            SheetRow(
                text = stringResource(R.string.machine_edit),
                modifier = Modifier.testTag(MENU_EDIT_TAG),
                onClick = onEdit,
            )
            SheetRow(
                text = stringResource(R.string.machine_reconnect),
                enabled = menu.reconnectEnabled,
                modifier = Modifier.testTag(MENU_RECONNECT_TAG),
                onClick = onReconnect,
            )
            SheetRow(
                text = stringResource(R.string.machine_remove),
                danger = true,
                modifier = Modifier.testTag(MENU_REMOVE_TAG),
                onClick = onRemove,
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The confirmation before a removal: the spaces leave the app, and the agents keep running on the machine (SPEC §2.1).
 *
 * @param menu the machine to remove.
 * @param onConfirm called to remove it.
 * @param onCancel called to keep it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveMachineSheet(
    menu: MachineMenu,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = onCancel, modifier = Modifier.testTag(REMOVE_MACHINE_SHEET_TAG)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.machine_confirm_remove_title, menu.name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.machine_confirm_remove_text),
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
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(REMOVE_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.common_remove))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** The test tag of the confirm button of the removal sheet. */
const val REMOVE_CONFIRM_TAG: String = "remove-confirm"

/**
 * The host-key sheet a card opens. An unknown key asks the user to trust it, with Trust and connect. A changed key is
 * refused: it shows both fingerprints, and offers only Remove machine and Close, never a way to trust the new key (SPEC §6).
 *
 * @param prompt the host-key decision the machine needs.
 * @param onTrust called to trust the presented key and connect. Used by the unknown key only.
 * @param onRemove called to ask for the machine's removal. Used by the changed key only.
 * @param onClose called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostKeySheet(
    prompt: HostKeyPrompt,
    onTrust: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(dragHandle = { SheetDragHandle() }, onDismissRequest = onClose, modifier = Modifier.testTag(HOST_KEY_SHEET_TAG)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (prompt) {
                is HostKeyPrompt.Unknown -> {
                    Text(
                        text = stringResource(R.string.add_host_key_title, prompt.name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.add_host_key_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalWhistlTokens.current.muted,
                    )
                    AlgorithmLine(prompt.algorithm)
                    FingerprintBlock(label = null, value = prompt.fingerprint)
                    Button(
                        onClick = onTrust,
                        colors = whistlButtonColors(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(HOST_KEY_TRUST_TAG),
                    ) {
                        Text(stringResource(R.string.add_trust))
                    }
                }

                is HostKeyPrompt.Changed -> {
                    Text(
                        text = stringResource(R.string.add_host_key_changed_title, prompt.name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.add_host_key_changed_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AlgorithmLine(prompt.algorithm)
                    FingerprintBlock(label = stringResource(R.string.add_host_key_trusted), value = prompt.expected)
                    FingerprintBlock(label = stringResource(R.string.add_host_key_presented), value = prompt.actual)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                            Text(stringResource(R.string.common_close))
                        }
                        Button(
                            onClick = onRemove,
                            colors = whistlButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(HOST_KEY_REMOVE_TAG),
                        ) {
                            Text(stringResource(R.string.machine_remove))
                        }
                    }
                }
            }
            if (prompt is HostKeyPrompt.Unknown) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The key type of a host key, as a line of text.
 *
 * @param algorithm the key type, for example `ssh-ed25519`.
 */
@Composable
internal fun AlgorithmLine(algorithm: String) {
    Text(
        text = stringResource(R.string.add_host_key_algorithm, algorithm),
        style = MaterialTheme.typography.bodySmall,
        color = LocalWhistlTokens.current.muted,
    )
}

/**
 * A host-key fingerprint in monospace, with an optional label above it.
 *
 * @param label the label, or `null` for none.
 * @param value the fingerprint, `SHA256:…`.
 */
@Composable
internal fun FingerprintBlock(label: String?, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = LocalWhistlTokens.current.muted,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * One row of a sheet's actions: a full-width row, 48 dp tall, red when it is dangerous and muted when it is off.
 *
 * @param text the action's label.
 * @param enabled whether it can be tapped.
 * @param danger whether it is a destructive action.
 * @param modifier the modifier for the row.
 * @param onClick called when tapped.
 */
@Composable
internal fun SheetRow(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> LocalWhistlTokens.current.muted
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
