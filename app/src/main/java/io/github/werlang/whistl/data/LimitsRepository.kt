package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcClient
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
            val result = try {
                run(machineId, client)
            } catch (e: HcException) {
                LimitsResult.Failed(e.message ?: "the machine refused the request")
            }
            answers.value = answers.value + (machineId to result)
        } finally {
            lease.release()
        }
    }

    /**
     * Runs the command with the first interpreter the machine has.
     *
     * The script travels as the argument of `-c`, so nothing is written on the machine, but an interpreter still has to
     * exist there. `python3` is on macOS, on the distributions and on WSL; `python` is the fallback for the machines that only
     * have the unsuffixed name. A machine with neither is a reason, not a silent empty card.
     */
    private suspend fun run(machineId: String, client: HcClient): LimitsResult {
        var last: LimitsResult = LimitsResult.Failed("no python3 on $machineId")
        for (interpreter in INTERPRETERS) {
            val exec = try {
                client.machineExec(listOf(interpreter, "-c", script))
            } catch (e: HcException) {
                return LimitsResult.Failed(e.message ?: "the machine refused the request")
            }
            if (exec.timedOut) return LimitsResult.Failed("the limits command timed out")
            if (exec.code == 0) return LimitsResult.Done(parseLimits(exec.stdout))
            val stderr = exec.stderr.trim()
            // 127 is "command not found"; the message differs between shells and cores, so both are checked.
            val missing = exec.code == 127 || stderr.contains("not found") || stderr.contains("No such file")
            last = LimitsResult.Failed(stderr.ifEmpty { "the limits command exited ${exec.code}" }.take(200))
            if (!missing) return last
        }
        return last
    }

    companion object {
        /** The interpreters to try, in order: the modern name first, the legacy one as a fallback. */
        val INTERPRETERS: List<String> = listOf("python3", "python")
    }
}
