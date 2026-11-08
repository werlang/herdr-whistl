package io.github.werlang.whistl.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.werlang.whistl.R
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.ui.theme.LocalWhistlTokens

/**
 * The label resource for a status (SPEC §2.4, §3). A plain program with no status reads "terminal".
 *
 * @param status the agent's status, or `null` for a plain program.
 * @return the string resource.
 */
@StringRes
fun statusLabelRes(status: AgentStatus?): Int = when (status) {
    AgentStatus.Working -> R.string.status_working
    AgentStatus.Blocked -> R.string.status_needs_you
    AgentStatus.Done -> R.string.status_done
    AgentStatus.Idle -> R.string.status_idle
    AgentStatus.Error -> R.string.status_error
    null -> R.string.status_plain
}

/**
 * The label of an agent (V3, phase 4b). A terminal that is not live is "finished" whatever its last status was; a live terminal
 * that is waiting for the user is "needs you" (the same rule as the badge, `isWaitingForUser`); otherwise its status.
 *
 * @param live whether the terminal is live.
 * @param waiting whether the terminal is live and blocked (`isWaitingForUser()`).
 * @param status the agent's last status, or `null` for a plain program.
 * @return the string resource.
 */
@StringRes
fun agentLabelRes(live: Boolean, waiting: Boolean, status: AgentStatus?): Int = when {
    !live -> R.string.status_finished
    waiting -> R.string.status_needs_you
    else -> statusLabelRes(status)
}

/**
 * The colour of a status dot (SPEC §3). A plain program uses the muted colour; a terminal that is not live uses the idle
 * colour, whatever its last status.
 *
 * @param status the agent's status, or `null` for a plain program.
 * @param live whether the terminal is live.
 * @return the colour from the theme.
 */
@Composable
fun statusColor(status: AgentStatus?, live: Boolean = true): Color {
    val tokens = LocalWhistlTokens.current
    if (!live) return tokens.idle
    return when (status) {
        AgentStatus.Working -> tokens.working
        AgentStatus.Blocked -> tokens.blocked
        AgentStatus.Done -> tokens.done
        AgentStatus.Idle -> tokens.idle
        AgentStatus.Error -> MaterialTheme.colorScheme.error
        null -> tokens.muted
    }
}
