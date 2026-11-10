package io.github.werlang.whistl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.data.Refusal
import io.github.werlang.whistl.data.UpdateProblem
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/** The test tag of the update card, so the screenshot and the tests can find it. */
const val UPDATE_CARD_TAG: String = "settings-update-card"

/**
 * The Updates card (phase 9, SPEC §2.7): the version installed, one button whose label follows the state, and one line
 * saying what happened. Every message is a fact about what the app did — nothing is optimistic.
 *
 * @param versionName the version this build carries.
 * @param update what the last check or download left behind.
 * @param onCheck asks the feed.
 * @param onDownload fetches the release the check offered.
 * @param onInstall hands the verified file to the installer.
 * @param onAllow opens the system screen for the install permission.
 */
@Composable
fun UpdateSection(
    versionName: String,
    update: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(UPDATE_CARD_TAG)) {
        Text(
            text = stringResource(R.string.settings_updates),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_version, versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    status(update)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalWhistlTokens.current.muted,
                        )
                    }
                }
                if (update is UpdateUiState.Downloading) {
                    val percent = update.percent
                    if (percent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp))
                    } else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                action(update, onCheck, onDownload, onInstall, onAllow)?.let { (label, click) ->
                    TextButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text = label)
                    }
                }
            }
        }
    }
}

@Composable
private fun status(update: UpdateUiState): String? = when (update) {
    UpdateUiState.Idle -> null
    UpdateUiState.Checking -> stringResource(R.string.settings_update_checking)
    is UpdateUiState.UpToDate -> stringResource(R.string.settings_update_uptodate)
    is UpdateUiState.Available -> stringResource(R.string.settings_update_available, update.version)
    is UpdateUiState.Downloading -> stringResource(R.string.settings_update_downloading)
    is UpdateUiState.Ready -> stringResource(R.string.settings_update_ready, update.version)
    is UpdateUiState.NeedsPermission -> stringResource(R.string.settings_update_needs_permission)
    is UpdateUiState.Refused -> stringResource(
        when (update.refusal) {
            Refusal.Checksum -> R.string.settings_update_refused_checksum
            Refusal.Signature -> R.string.settings_update_refused_signature
            Refusal.Oversized -> R.string.settings_update_refused_oversized
        },
    )
    is UpdateUiState.Failed -> stringResource(
        when (update.problem) {
            UpdateProblem.Network -> R.string.settings_update_failed_network
            UpdateProblem.Feed -> R.string.settings_update_failed_feed
            UpdateProblem.NoAsset -> R.string.settings_update_failed_noasset
            UpdateProblem.Install -> R.string.settings_update_failed_install
        },
    )
}

@Composable
private fun action(
    update: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllow: () -> Unit,
): Pair<String, () -> Unit>? = when (update) {
    UpdateUiState.Idle, UpdateUiState.Checking -> stringResource(R.string.settings_update_check) to onCheck
    is UpdateUiState.UpToDate -> stringResource(R.string.settings_update_check) to onCheck
    is UpdateUiState.Available -> stringResource(R.string.settings_update_download, update.version) to onDownload
    is UpdateUiState.Downloading -> null
    is UpdateUiState.Ready -> stringResource(R.string.settings_update_install) to onInstall
    is UpdateUiState.NeedsPermission -> stringResource(R.string.settings_update_allow) to onAllow
    is UpdateUiState.Refused -> stringResource(R.string.settings_update_check) to onCheck
    is UpdateUiState.Failed -> stringResource(R.string.settings_update_check) to onCheck
}
