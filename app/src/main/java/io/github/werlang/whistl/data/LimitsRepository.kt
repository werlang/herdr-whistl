package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.domain.LimitsReport
import io.github.werlang.whistl.domain.parseLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The answer of the limits command on one machine (0.7.4).
 *
 * The command runs **on the machine that holds the credentials**, over the core's `machine.exec`, so there is no server in
 * the middle: the app ships the script in its own assets and runs it where the CLIs live.
 */
sealed interface LimitsResult {
    /** The document came back and was read. */
    data class Done(val report: LimitsReport) : LimitsResult

    /** The machine has no live connection, so nothing could be asked. */
    data object Offline : LimitsResult

    /** The command ran but could not answer: no `python3`, a timeout, or output that is not the document. */
    data class Failed(val why: String) : LimitsResult
}

/** The subscription limits of the coding agents, asked on each machine over the core. */
interface LimitsRepository {
    /** The last answer per machine id. Empty until a refresh ran. */
    fun reports(): StateFlow<Map<String, LimitsResult>>

    /**
     * Runs the limits command on the machine and keeps its answer.
     *
     * @param machineId the machine to ask.
     */
    suspend fun refresh(machineId: String)
}

/**
 * The default [LimitsRepository]: one `machine.exec` per machine, no file written, nothing installed.
 *
 * The script is passed as the argument of `python3 -c`, so no shell is involved and no path has to exist on the machine.
 *
 * @param machines where the live clients come from.
 * @param script the command, as the app ships it.
 */
class DefaultLimitsRepository(
    private val machines: MachinesRepository,
    private val script: String,
) : LimitsRepository {
    private val answers = MutableStateFlow<Map<String, LimitsResult>>(emptyMap())

    override fun reports(): StateFlow<Map<String, LimitsResult>> = answers.asStateFlow()

    override suspend fun refresh(machineId: String) {
        val lease = try {
            machines.acquire(machineId)
        } catch (e: Exception) {
            answers.value = answers.value + (machineId to LimitsResult.Offline)
            return
        }
        try {
            val client = lease.client() ?: run {
                answers.value = answers.value + (machineId to LimitsResult.Offline)
                return
            }
            val exec = try {
                client.machineExec(ARGV_PREFIX + script)
            } catch (e: HcException) {
                answers.value = answers.value + (machineId to LimitsResult.Failed(e.message ?: "the machine refused the request"))
                return
            }
            val result = when {
                exec.code != 0 -> LimitsResult.Failed(
                    exec.stderr.trim().ifEmpty { "the limits command exited ${exec.code}" }.take(200),
                )
                exec.timedOut -> LimitsResult.Failed("the limits command timed out")
                else -> LimitsResult.Done(parseLimits(exec.stdout))
            }
            answers.value = answers.value + (machineId to result)
        } finally {
            lease.release()
        }
    }

    companion object {
        /** The interpreter and the flag that make the script travel as an argument, with no file on the machine. */
        val ARGV_PREFIX: List<String> = listOf("python3", "-c")
    }
}
