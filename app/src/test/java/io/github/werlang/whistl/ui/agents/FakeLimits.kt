package io.github.werlang.whistl.ui.agents

import io.github.werlang.whistl.data.LimitsRepository
import io.github.werlang.whistl.data.LimitsResult
import io.github.werlang.whistl.domain.LimitWindow
import io.github.werlang.whistl.domain.LimitsReport
import io.github.werlang.whistl.domain.ProviderLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A limits repository that answers with a fixed document and counts its asks, so a test can prove the sheet asked once
 * (0.7.5).
 *
 * @param answer what the machine reports, per machine id; a machine not in the map reports nothing.
 */
class FakeLimits(
    private val answer: Map<String, LimitsResult> = mapOf(
        "mac" to LimitsResult.Done(
            LimitsReport(
                listOf(
                    ProviderLimits(
                        id = "codex",
                        ok = true,
                        error = null,
                        cached = false,
                        windows = listOf(LimitWindow("7d", 78.0, 1_800_000_000_000L)),
                    ),
                    ProviderLimits(
                        id = "claude",
                        ok = false,
                        error = "no Claude limits token on this machine",
                        cached = false,
                        windows = emptyList(),
                        errorCode = "claude_not_configured",
                    ),
                ),
            ),
        ),
    ),
) : LimitsRepository {
    private val reports = MutableStateFlow<Map<String, LimitsResult>>(emptyMap())

    /** How many times the machines were asked, in order. */
    val asked: MutableList<String> = mutableListOf()

    override fun reports(): StateFlow<Map<String, LimitsResult>> = reports

    /** When set, a refresh waits for it before answering: a test can hold the command in flight. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun refresh(machineId: String) {
        asked += machineId
        gate?.await()
        answer[machineId]?.let { reports.value = reports.value + (machineId to it) }
    }
}
