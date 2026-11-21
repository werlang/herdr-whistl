package io.github.werlang.whistl.domain

import io.github.werlang.whistl.data.LimitsResult
import kotlin.math.roundToInt

/**
 * One provider window that reached the usage threshold and is worth announcing (0.7.9).
 *
 * @property machineId the machine the limits were asked on.
 * @property providerId the provider's name in the document: `claude`, `codex`, `opencode`.
 * @property providerTitle the provider as a person reads it ("Claude", "Codex").
 * @property windowLabel the window's short name ("5h", "7d").
 * @property usedPercent the percentage spent, as the provider reported it.
 * @property resetsAtMs when the window resets, in Unix milliseconds, `null` when unknown.
 */
data class LimitAlert(
    val machineId: String,
    val providerId: String,
    val providerTitle: String,
    val windowLabel: String,
    val usedPercent: Double,
    val resetsAtMs: Long?,
) {
    /** The percentage spent, rounded for display and kept between 0 and 100. */
    val usedPercentRounded: Int get() = usedPercent.roundToInt().coerceIn(0, 100)

    /** The percentage left, rounded for display and kept between 0 and 100. */
    val remainingPercent: Int get() = (100.0 - usedPercent).roundToInt().coerceIn(0, 100)
}

/**
 * What counts as a window running out (0.7.9): 90% spent, so 10% or less is left.
 *
 * A window is announced once per reset cycle. The key carries the machine, the provider, the window and the reset time,
 * so a new cycle alerts again while an unchanged one does not. A report that is not a [LimitsResult.Done] says nothing
 * about usage, so it neither alerts nor forgets: a machine that goes offline and comes back does not repeat the alert.
 */
object LimitAlerts {
    /** The usage at which a window is announced: 90% spent. */
    const val THRESHOLD_USED_PERCENT: Double = 90.0

    /**
     * The key that identifies one window in one reset cycle.
     *
     * @param machineId the machine the limits were asked on.
     * @param providerId the provider's name in the document.
     * @param windowLabel the window's short name.
     * @param resetsAtMs when the window resets, or `null` when unknown.
     * @return the key, stable for the same window and cycle.
     */
    fun key(machineId: String, providerId: String, windowLabel: String, resetsAtMs: Long?): String =
        "$machineId/$providerId/$windowLabel/${resetsAtMs ?: 0L}"

    /**
     * The windows at or above the threshold that were not announced in their cycle yet.
     *
     * @param alreadyAlerted the keys already announced, from the previous call.
     * @param reports the last answer of each machine, as [LimitsResult].
     * @return the alerts to post, and the keys to pass to the next call. A window that fell below the threshold loses
     * its key, so crossing it again alerts again.
     */
    fun between(alreadyAlerted: Set<String>, reports: Map<String, LimitsResult>): Pair<List<LimitAlert>, Set<String>> {
        val nextAlerted = alreadyAlerted.toMutableSet()
        val alerts = mutableListOf<LimitAlert>()
        for ((machineId, result) in reports) {
            // Offline and Failed are silent: no numbers, and no reason to forget what was already announced.
            if (result !is LimitsResult.Done) continue
            for (provider in result.report.providers) {
                if (!provider.ok) continue
                for (window in provider.windows) {
                    val used = window.usedPercent ?: continue
                    val k = key(machineId, provider.id, window.label, window.resetsAtMs)
                    if (used >= THRESHOLD_USED_PERCENT) {
                        if (k !in alreadyAlerted) {
                            alerts += LimitAlert(
                                machineId = machineId,
                                providerId = provider.id,
                                providerTitle = provider.title,
                                windowLabel = window.label,
                                usedPercent = used,
                                resetsAtMs = window.resetsAtMs,
                            )
                        }
                        nextAlerted += k
                    } else {
                        nextAlerted -= k
                    }
                }
            }
        }
        return alerts to nextAlerted.toSet()
    }
}
