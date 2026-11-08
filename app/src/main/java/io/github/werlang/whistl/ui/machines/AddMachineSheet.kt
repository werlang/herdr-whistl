package io.github.werlang.whistl.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.components.MinTouchTarget
import io.github.werlang.whistl.ui.components.SegmentOption
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens
import io.github.werlang.whistl.ui.theme.WhistlMono

/** The test tag of the Add machine sheet. */
const val ADD_MACHINE_SHEET_TAG: String = "add-machine-sheet"

/** The test tag of the host field. */
const val ADD_HOST_TAG: String = "add-host"

/** The test tag of the user field. */
const val ADD_USER_TAG: String = "add-user"

/** The test tag of the port field. */
const val ADD_PORT_TAG: String = "add-port"

/** The test tag of the display name field. */
const val ADD_NAME_TAG: String = "add-name"

/** The test tag of the password field, in password mode. */
const val ADD_PASSWORD_TAG: String = "add-password"

/** The test tag of the SSH key segment. */
const val ADD_AUTH_KEY_TAG: String = "add-auth-key"

/** The test tag of the password segment. */
const val ADD_AUTH_PASSWORD_TAG: String = "add-auth-password"

/** The test tag of the Copy button of the public key. */
const val ADD_COPY_KEY_TAG: String = "add-copy-key"

/** The test tag of the Connect button. */
const val ADD_CONNECT_TAG: String = "add-connect"

/** The test tag of the Trust and connect button of the unknown-key stage. */
const val ADD_TRUST_TAG: String = "add-trust"

/** The test tag of the Retry button. */
const val ADD_RETRY_TAG: String = "add-retry"

/** The test tag of the Remove machine button of the changed-key stage. */
const val ADD_REMOVE_TAG: String = "add-remove"

/** The test tag of the Close button. */
const val ADD_CLOSE_TAG: String = "add-close"

/**
 * The Add machine sheet (SPEC §2.1): the form, then the first connection. Connect checks the form and adds the machine; the
 * sheet then shows what the connection needs: a host key to trust, a changed key that refuses the connection, a refused login
 * or an unreachable machine with Retry, or the hc version it cannot speak. Connected closes the sheet.
 *
 * Each stage has one primary action, next to Close, in the sheet's bottom row: Connect, Trust and connect, Remove machine (for
 * a changed key, the only action besides Close), or Retry.
 *
 * The password is typed into this sheet only. It is handed to the view model at Connect as a `CharArray`, and the typed text
 * is dropped as soon as the flow leaves the form. It is never part of [state].
 *
 * @param state what the sheet shows.
 * @param onHost called when the host text changes.
 * @param onUser called when the user name changes.
 * @param onPort called when the port text changes.
 * @param onName called when the display name changes.
 * @param onAuth called when the login method changes.
 * @param onConnect called with the password in password mode, or `null` in key mode.
 * @param onTrust called to trust the presented host key and connect.
 * @param onRetry called to try the connection again.
 * @param onRemove called to forget the machine that was added.
 * @param onCopyKey called to copy the device's public key.
 * @param onClose called when the sheet closes, by a button or by dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMachineSheet(
    state: AddMachineUiState,
    onHost: (String) -> Unit,
    onUser: (String) -> Unit,
    onPort: (String) -> Unit,
    onName: (String) -> Unit,
    onAuth: (AuthChoice) -> Unit,
    onConnect: (CharArray?) -> Unit,
    onTrust: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onCopyKey: () -> Unit,
    onClose: () -> Unit,
) {
    var password by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(state.stage) {
        if (state.stage != AddStage.Form) password = TextFieldValue("")
    }
    val primary: PrimaryAction? = when (state.stage) {
        AddStage.Form -> PrimaryAction(
            label = R.string.add_connect,
            tag = ADD_CONNECT_TAG,
            // Connect waits for every required field: host and user, and the password in password mode.
            enabled = state.canConnect && (state.auth == AuthChoice.Key || password.text.isNotEmpty()),
            danger = false,
            onClick = { onConnect(if (state.auth == AuthChoice.Password) password.text.toCharArray() else null) },
        )

        AddStage.HostKeyUnknown -> PrimaryAction(R.string.add_trust, ADD_TRUST_TAG, true, false, onTrust)
        AddStage.HostKeyChanged -> PrimaryAction(R.string.machine_remove, ADD_REMOVE_TAG, true, true, onRemove)
        AddStage.LoginRefused, AddStage.Unreachable -> PrimaryAction(R.string.add_retry, ADD_RETRY_TAG, true, false, onRetry)
        AddStage.Connecting, AddStage.UnsupportedCore, AddStage.Connected -> null
    }
    // Opens fully expanded: the form is taller than a partly open sheet, and its fields and actions must be reachable at once.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag(ADD_MACHINE_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.add_machine_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (state.stage == AddStage.Form) {
                FormFields(
                    state = state,
                    password = password,
                    onPasswordChange = { password = it },
                    onHost = onHost,
                    onUser = onUser,
                    onPort = onPort,
                    onName = onName,
                    onAuth = onAuth,
                    onCopyKey = onCopyKey,
                )
            } else {
                StagePanel(state = state)
            }
            ActionRow(primary = primary, onClose = onClose)
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * The one primary action of a stage, shown next to Close.
 *
 * @property label the action's label.
 * @property tag the action's test tag.
 * @property enabled whether it can be tapped.
 * @property danger whether it is destructive, in the error colour.
 * @property onClick called when tapped.
 */
private class PrimaryAction(
    val label: Int,
    val tag: String,
    val enabled: Boolean,
    val danger: Boolean,
    val onClick: () -> Unit,
)

/**
 * The form's fields: host, user, port, name, the login method, and the key or the password for it.
 *
 * @param state the sheet's state.
 * @param password the password text, held by the sheet only.
 * @param onPasswordChange called when the password text changes.
 * @param onHost called when the host text changes.
 * @param onUser called when the user name changes.
 * @param onPort called when the port text changes.
 * @param onName called when the display name changes.
 * @param onAuth called when the login method changes.
 * @param onCopyKey called to copy the public key.
 */
@Composable
private fun FormFields(
    state: AddMachineUiState,
    password: TextFieldValue,
    onPasswordChange: (TextFieldValue) -> Unit,
    onHost: (String) -> Unit,
    onUser: (String) -> Unit,
    onPort: (String) -> Unit,
    onName: (String) -> Unit,
    onAuth: (AuthChoice) -> Unit,
    onCopyKey: () -> Unit,
) {
    Field(
        value = state.host,
        onChange = onHost,
        label = R.string.add_field_host,
        placeholder = R.string.add_host_placeholder,
        problem = state.problems[AddField.Host],
        tag = ADD_HOST_TAG,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    Field(
        value = state.user,
        onChange = onUser,
        label = R.string.add_field_user,
        placeholder = R.string.add_user_placeholder,
        problem = state.problems[AddField.User],
        tag = ADD_USER_TAG,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
    )
    Field(
        value = state.port,
        onChange = onPort,
        label = R.string.add_field_port,
        placeholder = null,
        problem = state.problems[AddField.Port],
        tag = ADD_PORT_TAG,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        placeholderText = DEFAULT_SSH_PORT.toString(),
    )
    Field(
        value = state.name,
        onChange = onName,
        label = R.string.add_field_name,
        placeholder = null,
        problem = state.problems[AddField.Name],
        tag = ADD_NAME_TAG,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        placeholderText = state.defaultName,
    )
    Text(
        text = stringResource(R.string.add_field_auth),
        style = MaterialTheme.typography.labelLarge,
        color = LocalWhistlTokens.current.muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SegmentOption(
            label = stringResource(R.string.add_auth_key),
            selected = state.auth == AuthChoice.Key,
            onClick = { onAuth(AuthChoice.Key) },
            modifier = Modifier.weight(1f).testTag(ADD_AUTH_KEY_TAG),
        )
        SegmentOption(
            label = stringResource(R.string.add_auth_password),
            selected = state.auth == AuthChoice.Password,
            onClick = { onAuth(AuthChoice.Password) },
            modifier = Modifier.weight(1f).testTag(ADD_AUTH_PASSWORD_TAG),
        )
    }
    if (state.auth == AuthChoice.Key) {
        PublicKeyBlock(publicKey = state.publicKey, copied = state.publicKeyCopied, onCopy = onCopyKey)
    } else {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.add_field_password)) },
            singleLine = true,
            isError = state.problems[AddField.Password] != null,
            supportingText = problemMessage(state.problems[AddField.Password]),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(ADD_PASSWORD_TAG),
        )
    }
}

/**
 * One text field of the form: its label, its placeholder, and its problem under it.
 *
 * @param value the text.
 * @param onChange called when the text changes.
 * @param label the label string.
 * @param placeholder the placeholder string, or `null` for none.
 * @param problem the field's problem, or `null`.
 * @param tag the test tag of the field.
 * @param keyboard the keyboard the field asks for.
 * @param placeholderText a placeholder that is already text, used instead of [placeholder] when given.
 */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: Int?,
    problem: FieldProblem?,
    tag: String,
    keyboard: KeyboardOptions,
    placeholderText: String? = null,
) {
    val hint = placeholderText ?: placeholder?.let { stringResource(it) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholderMessage(hint),
        singleLine = true,
        isError = problem != null,
        supportingText = problemMessage(problem),
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * The placeholder of a field: its hint in the dim colour, or nothing.
 *
 * @param hint the hint text, or `null`.
 * @return the placeholder content, or `null`.
 */
@Composable
private fun placeholderMessage(hint: String?): (@Composable () -> Unit)? {
    if (hint == null) return null
    return { Text(hint, color = LocalWhistlTokens.current.dim) }
}

/**
 * The message under a field, for its problem.
 *
 * @param problem the problem, or `null` for no message.
 * @return the message, or `null`.
 */
@Composable
private fun problemMessage(problem: FieldProblem?): (@Composable () -> Unit)? {
    if (problem == null) return null
    val message = stringResource(problemRes(problem))
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}

/**
 * The string of each field problem.
 *
 * @param problem the problem.
 * @return the string resource.
 */
private fun problemRes(problem: FieldProblem): Int = when (problem) {
    FieldProblem.HostEmpty -> R.string.problem_host_empty
    FieldProblem.HostBadCharacters -> R.string.problem_host_bad_characters
    FieldProblem.HostBadFormat -> R.string.problem_host_bad_format
    FieldProblem.HostTooLong -> R.string.problem_host_too_long
    FieldProblem.UserEmpty -> R.string.problem_user_empty
    FieldProblem.UserBadCharacters -> R.string.problem_user_bad_characters
    FieldProblem.UserBadFormat -> R.string.problem_user_bad_format
    FieldProblem.UserTooLong -> R.string.problem_user_too_long
    FieldProblem.PortNotANumber -> R.string.problem_port_not_a_number
    FieldProblem.PortOutOfRange -> R.string.problem_port_out_of_range
    FieldProblem.NameEmpty -> R.string.problem_name_empty
    FieldProblem.NameTooLong -> R.string.problem_name_too_long
    FieldProblem.PasswordEmpty -> R.string.problem_password_empty
}

/**
 * This device's public key, in monospace, with the hint to add it to the machine and a Copy button (SPEC §2.1).
 *
 * @param publicKey the key line.
 * @param copied whether the key was copied since the sheet opened.
 * @param onCopy called to copy it.
 */
@Composable
private fun PublicKeyBlock(publicKey: String, copied: Boolean, onCopy: () -> Unit) {
    val tokens = LocalWhistlTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.add_public_key_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = publicKey,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = WhistlMono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.field, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
        Text(
            text = stringResource(R.string.add_public_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_COPY_KEY_TAG)) {
                Text(stringResource(R.string.add_public_key_copy))
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.add_public_key_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.muted,
                )
            }
        }
    }
}

/**
 * What the connection needs, after the machine was added: the reason, and for a host key the keys to compare. Its actions are
 * in the bottom row.
 *
 * @param state the sheet's state.
 */
@Composable
private fun StagePanel(state: AddMachineUiState) {
    val name = state.machineName ?: state.host
    val tokens = LocalWhistlTokens.current
    when (state.stage) {
        AddStage.Connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.add_connecting, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        AddStage.HostKeyUnknown -> {
            Text(
                text = stringResource(R.string.add_host_key_title, name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.add_host_key_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.muted,
            )
            AlgorithmLine(state.hostKeyAlgorithm.orEmpty())
            FingerprintBlock(label = null, value = state.hostKeyFingerprint.orEmpty())
        }

        AddStage.HostKeyChanged -> {
            Text(
                text = stringResource(R.string.add_host_key_changed_title, name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.add_host_key_changed_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AlgorithmLine(state.hostKeyAlgorithm.orEmpty())
            FingerprintBlock(label = stringResource(R.string.add_host_key_trusted), value = state.trustedFingerprint.orEmpty())
            FingerprintBlock(label = stringResource(R.string.add_host_key_presented), value = state.presentedFingerprint.orEmpty())
        }

        AddStage.LoginRefused -> Text(
            text = stringResource(R.string.error_login_refused),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        AddStage.Unreachable -> Text(
            text = stringResource(R.string.error_unreachable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        AddStage.UnsupportedCore -> Text(
            text = stringResource(R.string.error_unsupported_core),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        AddStage.Form, AddStage.Connected -> Unit
    }
}

/**
 * The sheet's bottom row: Close on the left, and the stage's primary action on the right when it has one.
 *
 * @param primary the primary action, or `null` for none.
 * @param onClose called to close the sheet.
 */
@Composable
private fun ActionRow(primary: PrimaryAction?, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_CLOSE_TAG)) {
            Text(stringResource(R.string.add_close))
        }
        Spacer(Modifier.weight(1f))
        if (primary != null) {
            Button(
                onClick = primary.onClick,
                enabled = primary.enabled,
                colors = if (primary.danger) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(primary.tag),
            ) {
                Text(stringResource(primary.label))
            }
        }
    }
}
