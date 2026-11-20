package io.github.werlang.whistl.ui.agents

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.theme.WhistlMono
import io.github.werlang.whistl.domain.LimitWindow
import io.github.werlang.whistl.domain.ProviderLimits
import io.github.werlang.whistl.data.LimitsResult
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
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
    onShowLimits: () -> Unit,
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
                    // The limits belong to the machine the agent runs on, not to the agent: the sheet says so in its header
                    // and shows every provider that answered there (0.7.5).
                    SheetAction(stringResource(R.string.agents_action_limits), onShowLimits)
                    AgentLimitsBlock(menu.limitsLoading, menu.limits)
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

/**
 * The limits inside the action sheet: what the agent's machine reported, provider by provider.
 *
 * While the command runs, a line says so; when it answers, each provider gets its windows with the share left and the reset;
 * when it cannot answer, the reason is shown as written. A failed provider is not hidden: "could not ask" and "nothing
 * left" must not look alike.
 *
 * @param loading whether the command is still running.
 * @param result the answer, or `null` before the user asked.
 */
@Composable
fun AgentLimitsBlock(loading: Boolean, result: LimitsResult?) {
    val tokens = LocalWhistlTokens.current
    if (!loading && result == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            loading -> Text(
                text = stringResource(R.string.agents_limits_loading),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.dim,
            )
            result is LimitsResult.Done && result.report.providers.isEmpty() -> Text(
                text = stringResource(R.string.agents_limits_none),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.dim,
            )
            result is LimitsResult.Done -> result.report.providers.forEach { provider ->
                Text(
                    text = provider.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!provider.ok) {
                    val failure = limitsFailure(provider)
                    Text(
                        text = failure.text?.let { stringResource(it) }
                            ?: provider.error.orEmpty().ifEmpty { stringResource(R.string.agents_limits_failed) },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (failure.tone) {
                            LimitsFailureTone.Notice -> tokens.dim
                            LimitsFailureTone.Warning -> tokens.working
                            LimitsFailureTone.Error -> tokens.blocked
                        },
                    )
                } else {
                    provider.windows.forEach { window -> LimitRow(window, provider.cached) }
                }
            }
            result is LimitsResult.Failed -> Text(
                text = result.why,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.blocked,
            )
            result is LimitsResult.Offline -> Text(
                text = stringResource(R.string.agents_limits_offline),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.blocked,
            )
        }
    }
}

/** How loud a failed provider reads: a pending setup is a notice, an expired token a warning, anything else an error. */
internal enum class LimitsFailureTone { Notice, Warning, Error }

/**
 * What a failed provider shows.
 *
 * @property text the string resource for a failure the command explained, `null` when the provider's own reason is shown.
 * @property tone how loud the line is.
 */
internal data class LimitsFailure(@StringRes val text: Int?, val tone: LimitsFailureTone)

/**
 * The words and tone of a failed provider.
 *
 * The command sends a stable code for the Claude failures the app can explain, so the sheet says what to do instead of
 * repeating the machine's sentence. Pending setup is neutral, an expired token is a warning, and a provider without a code
 * keeps its own reason as an error.
 *
 * @param provider the provider that did not answer.
 * @return the string resource to show, or `null` for [ProviderLimits.error], and the tone.
 */
internal fun limitsFailure(provider: ProviderLimits): LimitsFailure = when (provider.errorCode) {
    "claude_token_setup_required" ->
        LimitsFailure(R.string.agents_limits_claude_setup, LimitsFailureTone.Notice)
    "claude_not_configured" ->
        LimitsFailure(R.string.agents_limits_claude_not_configured, LimitsFailureTone.Notice)
    "claude_token_expired" ->
        LimitsFailure(R.string.agents_limits_claude_expired, LimitsFailureTone.Warning)
    else -> LimitsFailure(null, LimitsFailureTone.Error)
}

/** One window: its name, the bar, and what is left with the reset time. */
@Composable
private fun LimitRow(window: LimitWindow, cached: Boolean) {
    val tokens = LocalWhistlTokens.current
    val used = window.usedPercent
    val color = when {
        used == null -> tokens.muted
        used >= 90 -> tokens.blocked
        used >= 70 -> tokens.working
        else -> tokens.done
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // The name on the left, the numbers on the right: on a phone the single line clipped the numbers, which are the
        // part that matters.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = window.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = WhistlMono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = limitText(used, window.resetsAtMs, cached),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = WhistlMono,
                color = color,
                maxLines = 1,
            )
        }
        // The bar is what makes 91% and 96% different at a glance.
        LinearProgressIndicator(
            progress = { ((used ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            trackColor = tokens.field,
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * "22 left · 4h 59m", or "—" when the provider gave no percentage.
 *
 * @param used the percentage spent, or `null`.
 * @param resetsAtMs when the window resets, or `null`.
 * @param cached whether these numbers are the last good answer.
 * @return the label.
 */
@Composable
private fun limitText(used: Double?, resetsAtMs: Long?, cached: Boolean): String {
    if (used == null) return stringResource(R.string.agents_limits_unknown)
    val remaining = (100 - used).toInt()
    val left = pluralStringResource(R.plurals.agents_limits_left, remaining, remaining)
    val reset = resetIn(resetsAtMs)
    val cachedWord = stringResource(R.string.agents_limits_cached)
    // Every composition is a string resource, so a separator or a dash can be translated with the rest of the sentence.
    return when {
        reset != null && cached -> stringResource(R.string.agents_limits_join3, left, reset, cachedWord)
        reset != null -> stringResource(R.string.agents_limits_join, left, reset)
        cached -> stringResource(R.string.agents_limits_join, left, cachedWord)
        else -> left
    }
}

/**
 * The time until a window resets: "4h 59m", "3d 4h", or `null` when unknown.
 *
 * @param resetsAtMs the reset time in Unix milliseconds.
 * @return the label, or `null`.
 */
@Composable
private fun resetIn(resetsAtMs: Long?): String? {
    if (resetsAtMs == null) return null
    val left = resetsAtMs - System.currentTimeMillis()
    if (left <= 0) return stringResource(R.string.machine_limits_reset_now)
    val minutes = left / 60_000
    val days = minutes / 1_440
    val hours = (minutes % 1_440) / 60
    val rest = minutes % 60
    return when {
        days > 0 -> stringResource(R.string.machine_limits_reset_days, days, hours)
        hours > 0 -> stringResource(R.string.machine_limits_reset_hours, hours, rest)
        else -> stringResource(R.string.machine_limits_reset_minutes, rest)
    }
}
