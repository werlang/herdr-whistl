package io.github.werlang.whistl.domain

import io.github.werlang.whistl.data.LimitsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 90% limit alert (0.7.9): one window, one reset cycle, one notification.
 *
 * The threshold is crossed by the reported usage, not by its rounded form: 89.9% stays silent even though it would be
 * shown as 90%. A cycle is identified by its reset time, so the same window alerts again only when the reset moves.
 */
class LimitAlertsTest {
    /** Just below the threshold is not an alert. */
    @Test
    fun belowTheThresholdDoesNotAlert() {
        val (alerts, next) = LimitAlerts.between(
            emptySet(),
            done("m", provider(windows = listOf(window(used = 89.9)))),
        )

        assertTrue(alerts.isEmpty())
        assertTrue(next.isEmpty())
    }

    /** The threshold itself and every value above it alert, with the displayed percentages rounded. */
    @Test
    fun atOrAboveTheThresholdAlertsWithRoundedPercentages() {
        val (at, _) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 90.0)))))
        val (above, _) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 95.5)))))
        val (full, _) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 100.0)))))

        assertEquals(90, at.single().usedPercentRounded)
        assertEquals(10, at.single().remainingPercent)
        assertEquals(96, above.single().usedPercentRounded)
        assertEquals(5, above.single().remainingPercent)
        assertEquals(100, full.single().usedPercentRounded)
        assertEquals(0, full.single().remainingPercent)
    }

    /** The same window in the same cycle is announced only once. */
    @Test
    fun theSameWindowInTheSameCycleDoesNotAlertAgain() {
        val (first, next) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 92.0)))))
        val (second, _) = LimitAlerts.between(next, done("m", provider(windows = listOf(window(used = 93.0)))))

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    /** A new reset time is a new cycle, so the same window alerts again. */
    @Test
    fun aNewResetCycleAlertsAgain() {
        val (first, next) = LimitAlerts.between(
            emptySet(),
            done("m", provider(windows = listOf(window(used = 92.0, resetsAtMs = 1_000)))),
        )
        val (second, _) = LimitAlerts.between(
            next,
            done("m", provider(windows = listOf(window(used = 92.0, resetsAtMs = 2_000)))),
        )

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(2_000L, second.single().resetsAtMs)
    }

    /** Falling below the threshold re-arms the window for the next crossing. */
    @Test
    fun fallingBelowTheThresholdReArmsTheAlert() {
        val (first, next) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 92.0)))))
        val (dropped, rearmed) = LimitAlerts.between(next, done("m", provider(windows = listOf(window(used = 80.0)))))
        val (again, _) = LimitAlerts.between(rearmed, done("m", provider(windows = listOf(window(used = 91.0)))))

        assertEquals(1, first.size)
        assertTrue(dropped.isEmpty())
        assertTrue(rearmed.isEmpty())
        assertEquals(1, again.size)
    }

    /** An offline or failed answer neither alerts nor forgets a window that was already announced. */
    @Test
    fun offlineAndFailedKeepTheAlreadyAlertedKeys() {
        val (_, next) = LimitAlerts.between(emptySet(), done("m", provider(windows = listOf(window(used = 92.0)))))

        val (offlineAlerts, afterOffline) = LimitAlerts.between(next, mapOf("m" to LimitsResult.Offline))
        val (failedAlerts, afterFailed) = LimitAlerts.between(
            afterOffline,
            mapOf("m" to LimitsResult.Failed("the limits command timed out")),
        )
        val (backAlerts, _) = LimitAlerts.between(
            afterFailed,
            done("m", provider(windows = listOf(window(used = 92.0)))),
        )

        assertTrue(offlineAlerts.isEmpty())
        assertTrue(failedAlerts.isEmpty())
        assertTrue(backAlerts.isEmpty())
        assertEquals(next, afterOffline)
        assertEquals(next, afterFailed)
    }

    /** A provider that failed is ignored even when its stale windows claim the threshold was crossed. */
    @Test
    fun aProviderThatFailedIsIgnored() {
        val (alerts, next) = LimitAlerts.between(
            emptySet(),
            done("m", provider(ok = false, windows = listOf(window(used = 99.0)))),
        )

        assertTrue(alerts.isEmpty())
        assertTrue(next.isEmpty())
    }

    /** Machines, providers and windows are tracked independently, and a repeat of the whole map stays quiet. */
    @Test
    fun machinesProvidersAndWindowsAreTrackedIndependently() {
        val reports = mapOf(
            "a" to LimitsResult.Done(
                LimitsReport(
                    listOf(
                        provider(
                            id = "claude",
                            windows = listOf(
                                window(label = "5h", used = 91.0),
                                window(label = "7d", used = 95.0),
                            ),
                        ),
                        provider(id = "codex", windows = listOf(window(label = "7d", used = 90.0))),
                    ),
                ),
            ),
            "b" to LimitsResult.Done(
                LimitsReport(
                    listOf(provider(id = "opencode", windows = listOf(window(label = "rolling", used = 100.0)))),
                ),
            ),
        )

        val (alerts, next) = LimitAlerts.between(emptySet(), reports)
        val (repeated, repeatedNext) = LimitAlerts.between(next, reports)

        assertEquals(
            listOf("a/claude/5h", "a/claude/7d", "a/codex/7d", "b/opencode/rolling"),
            alerts.map { "${it.machineId}/${it.providerId}/${it.windowLabel}" },
        )
        assertEquals(
            listOf("Claude", "Claude", "Codex", "OpenCode"),
            alerts.map { it.providerTitle },
        )
        assertTrue(repeated.isEmpty())
        assertEquals(next, repeatedNext)
    }

    /** One machine's answer, as the repository would report it after a refresh. */
    private fun done(machineId: String, vararg providers: ProviderLimits): Map<String, LimitsResult> =
        mapOf(machineId to LimitsResult.Done(LimitsReport(providers.toList())))

    /** One provider with the windows it reported. */
    private fun provider(
        id: String = "claude",
        ok: Boolean = true,
        windows: List<LimitWindow> = emptyList(),
    ): ProviderLimits = ProviderLimits(
        id = id,
        ok = ok,
        error = if (ok) null else "the provider failed",
        cached = false,
        windows = windows,
    )

    /** One window at the reported usage. */
    private fun window(label: String = "5h", used: Double, resetsAtMs: Long? = 1_000L): LimitWindow =
        LimitWindow(label = label, usedPercent = used, resetsAtMs = resetsAtMs)
}
