package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.SpawnFailed
import io.github.werlang.whistl.core.rpc.StillLive
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.Terminal
import io.github.werlang.whistl.domain.TerminalState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The label on the throwaway terminals that resolve folders (phase 4 decision 1). Every terminal with this label is hidden
 * from the agents: the events reducer and the snapshot both drop it (see [isProbe]).
 */
const val PROBE_LABEL: String = "whistl.probe"

/**
 * Whether a terminal is a folder probe, by its [PROBE_LABEL] label. Probes are never agents.
 *
 * @return `true` when the terminal carries the probe label.
 */
fun Terminal.isProbe(): Boolean = labels.containsKey(PROBE_LABEL)

/** Why a space folder could not be resolved on its machine (phase 4 decision 1). */
enum class UnresolvableReason {
    /** hc could not start the probe in that folder: the folder does not exist, or cannot be entered (`spawn_failed`). */
    NotFound,

    /** The folder names another user's home (`~user/...`). Only `~` and `~/...` are expanded. */
    UnsupportedTilde,

    /** The probe did not finish within [FolderResolver.PROBE_TIMEOUT_MS]. */
    Timeout,

    /**
     * hc answered the probe with something that is not an absolute path, or with another error. The answer is not
     * shown: it could be terminal text.
     */
    BadOutput,

    /** The folder starts with `~`, and the machine's home directory could not be read. */
    NoHome,
}

/**
 * The result of resolving one space folder to an absolute path on its machine (phase 4 decision 1).
 */
sealed interface FolderResolution {
    /**
     * The folder is a directory that hc can enter. [path] is the absolute path the probe printed.
     *
     * @property path the absolute path on the machine.
     */
    data class Resolved(val path: String) : FolderResolution

    /**
     * The folder could not be resolved. The space row shows the reason; agents labelled for the space still match by label.
     *
     * @property reason why.
     */
    data class Unresolvable(val reason: UnresolvableReason) : FolderResolution
}

/**
 * The resolved paths kept on this device, per machine and folder as typed (phase 4 decision 1). They are never written to
 * the `whistl` document. Tests use an in-memory fake; the app uses a SharedPreferences implementation in `platform/`.
 */
interface ResolvedFolderCache {
    /**
     * The path last resolved for a folder on a machine.
     *
     * @param machineId the machine id.
     * @param folder the folder exactly as typed in the space.
     * @return the absolute path, or `null` when none was stored.
     */
    fun get(machineId: String, folder: String): String?

    /**
     * Stores the path resolved for a folder on a machine, replacing any old one.
     *
     * @param machineId the machine id.
     * @param folder the folder exactly as typed in the space.
     * @param path the absolute path the probe printed.
     */
    fun put(machineId: String, folder: String, path: String)

    /**
     * Drops every path kept for a machine. Called when the machine is removed (SPEC §2).
     *
     * @param machineId the machine id.
     */
    fun remove(machineId: String)
}

/**
 * Resolves the space folders of one machine connection to absolute paths (phase 4 decision 1, amended: the home directory
 * is read once per connection).
 *
 * Create one instance per connection. It memoises the result per folder, so each folder is probed at most once on that
 * connection, and it reads the home directory at most once. A folder is resolved like this:
 * 1. `~` becomes the home directory, and `~/x` becomes home plus `/x`. `~user` is [UnresolvableReason.UnsupportedTilde];
 *    any other folder is used as typed. No shell string is built from the folder: argv is always a list.
 * 2. A probe runs `pwd` in that folder with [PROBE_LABEL], and the probe is polled until it has finished (every
 *    [POLL_INTERVAL_MS] of time, at most [PROBE_TIMEOUT_MS]). Its output is read; the last non-blank line must be an
 *    absolute path.
 * 3. The probe terminal is removed in every case, including a failure, a timeout and a cancelled coroutine.
 *
 * A resolved path is kept in [cache]. A transport failure is not a verdict: it is thrown to the caller, and nothing is
 * remembered for the folder.
 *
 * @param machineId the machine this connection is to.
 * @param client the connection's command client.
 * @param cache where resolved paths are kept.
 */
class FolderResolver(
    private val machineId: String,
    private val client: HcClient,
    private val cache: ResolvedFolderCache,
) {
    private val mutex = Mutex()
    private val results = HashMap<String, FolderResolution>()
    private var homeRead = false
    private var home: String? = null

    /**
     * Resolves [folder] on this connection.
     *
     * @param folder the folder exactly as typed in the space.
     * @return the resolution; the same one on every later call on this connection.
     * @throws TransportLost when the connection is lost during the probe. Nothing is remembered for [folder] then.
     */
    suspend fun resolve(folder: String): FolderResolution = mutex.withLock {
        results[folder] ?: compute(folder).also { result ->
            results[folder] = result
            if (result is FolderResolution.Resolved) cache.put(machineId, folder, result.path)
        }
    }

    private suspend fun compute(folder: String): FolderResolution {
        val tilde = folder.startsWith("~")
        if (tilde && folder != "~" && !folder.startsWith("~/")) {
            return FolderResolution.Unresolvable(UnresolvableReason.UnsupportedTilde)
        }
        val target = if (tilde) {
            val home = homeDirectory() ?: return FolderResolution.Unresolvable(UnresolvableReason.NoHome)
            expandTilde(folder, home)
        } else {
            folder
        }
        return probe(target)
    }

    /** Reads the home directory with a constant argv, once per connection. A failure is remembered as no home. */
    private suspend fun homeDirectory(): String? {
        if (!homeRead) {
            homeRead = true
            home = try {
                val exec = client.machineExec(PRINTENV_HOME)
                if (exec.code == 0 && !exec.timedOut) lastNonBlankLine(exec.stdout)?.takeIf { it.startsWith("/") } else null
            } catch (e: TransportLost) {
                throw e
            } catch (e: HcException) {
                null
            }
        }
        return home
    }

    /** Runs the probe in [cwd], waits for it, reads its answer, and removes it. */
    private suspend fun probe(cwd: String): FolderResolution {
        val terminal = try {
            client.terminalRun(argv = PWD, cwd = cwd, labels = mapOf(PROBE_LABEL to "1"))
        } catch (e: SpawnFailed) {
            return FolderResolution.Unresolvable(UnresolvableReason.NotFound)
        } catch (e: TransportLost) {
            throw e
        } catch (e: HcException) {
            return FolderResolution.Unresolvable(UnresolvableReason.BadOutput)
        }
        var finished = false
        try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { awaitFinished(terminal.address) }
                ?: return FolderResolution.Unresolvable(UnresolvableReason.Timeout)
            finished = true
            val line = lastNonBlankLine(client.terminalRead(terminal.address).text)
            return if (line != null && line.startsWith("/")) {
                FolderResolution.Resolved(line)
            } else {
                FolderResolution.Unresolvable(UnresolvableReason.BadOutput)
            }
        } catch (e: TransportLost) {
            throw e
        } catch (e: HcException) {
            return FolderResolution.Unresolvable(UnresolvableReason.BadOutput)
        } finally {
            // A probe that did not finish may still be running, and hc refuses to remove a running terminal (kind `live`).
            removeProbe(terminal.address, mayBeLive = !finished)
        }
    }

    /** Polls the probe list until [address] shows as finished. The caller bounds the wait. */
    private suspend fun awaitFinished(address: String) {
        while (true) {
            val listed = client.terminalList(labels = mapOf(PROBE_LABEL to "1"), all = true).terminals
            if (listed.any { it.address == address && it.state == TerminalState.Finished }) return
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Removes the probe even when this coroutine was cancelled. A probe that may still run is killed first, and a removal
     * that hc refuses while the probe is still running (`live`) is retried for a bounded time. A removal that still fails is
     * ignored: the probe carries [PROBE_LABEL], so it is hidden from the agents either way.
     *
     * @param address the probe's terminal address.
     * @param mayBeLive `true` when the probe was not seen finished, so it may still be running.
     */
    private suspend fun removeProbe(address: String, mayBeLive: Boolean) {
        withContext(NonCancellable) {
            if (mayBeLive) {
                try {
                    client.terminalKill(address)
                } catch (e: HcException) {
                    // Already gone, or the connection is lost: the removal below decides.
                }
            }
            for (attempt in 1..RM_ATTEMPTS) {
                try {
                    client.terminalRm(address)
                    return@withContext
                } catch (e: StillLive) {
                    delay(POLL_INTERVAL_MS)
                } catch (e: HcException) {
                    return@withContext
                }
            }
        }
    }

    companion object {
        /** How long a probe may take before the folder is [UnresolvableReason.Timeout] (phase 4 brief: 5 s). */
        const val PROBE_TIMEOUT_MS: Long = 5_000L

        /** How often the probe's state is read while waiting (phase 4 brief: 100 ms). */
        const val POLL_INTERVAL_MS: Long = 100L

        /** How many times a removal refused as `live` is tried again, [POLL_INTERVAL_MS] apart (about 2 s). */
        const val RM_ATTEMPTS: Int = 20

        /** The probe's program. A constant argv: nothing from the folder is ever part of it. */
        private val PWD: List<String> = listOf("pwd")

        /** The home-directory read. A constant argv. */
        private val PRINTENV_HOME: List<String> = listOf("printenv", "HOME")

        /**
         * `~` or `~/...` with [home] substituted. Trailing slashes on [home] are dropped first, so a home of `/` gives `/x`.
         *
         * @param folder a folder that is `~` or starts with `~/`.
         * @param home the absolute home directory.
         * @return the expanded path.
         */
        internal fun expandTilde(folder: String, home: String): String {
            val base = home.trimEnd('/')
            return if (folder == "~") base.ifEmpty { "/" } else base + folder.substring(1)
        }

        /**
         * The last line of [text] that is not blank, trimmed. Terminal text is padded to the terminal width, so the
         * padding is removed too.
         *
         * @param text the text hc returned.
         * @return the line, or `null` when every line is blank.
         */
        internal fun lastNonBlankLine(text: String): String? =
            text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    }
}
