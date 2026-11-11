package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.TransportLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Why a folder could not be read. */
enum class FolderProblem {
    /** The machine is not connected. */
    Offline,

    /** The folder is not there, is not a directory, or cannot be read. */
    Unreadable,
}

/**
 * A folder as the picker shows it.
 *
 * @property path the folder itself, absolute on the machine.
 * @property parent the folder above it, or `null` at the root.
 * @property folders the directories inside it, by name, sorted.
 * @property problem why it could not be read, or `null` when it was.
 */
data class FolderListing(
    val path: String,
    val parent: String?,
    val folders: List<String>,
    val problem: FolderProblem? = null,
)

/** Reads a machine's directories, for choosing a space's folder without typing it (phase 9, SPEC §2.3). */
interface FolderBrowser {
    /**
     * Starts at the machine's home folder.
     *
     * @param machineId the machine to read.
     * @return the home folder and its subfolders.
     */
    suspend fun home(machineId: String): FolderListing

    /**
     * @param machineId the machine to read.
     * @param path an absolute path on that machine.
     * @return the folder and its subfolders.
     */
    suspend fun list(machineId: String, path: String): FolderListing
}

/**
 * [FolderBrowser] over `machine.exec`, with constant argv and the path passed as a positional parameter — never
 * interpolated into a shell string, so a folder called `$(rm -rf ~)` is a folder name and nothing else (SPEC §6).
 *
 * @param machines takes the machine's lease for the duration of a read.
 */
class ExecFolderBrowser(private val machines: MachinesRepository) : FolderBrowser {

    override suspend fun home(machineId: String): FolderListing {
        val lease = try {
            machines.acquire(machineId)
        } catch (e: NoSuchElementException) {
            return FolderListing(DEFAULT_HOME, null, emptyList(), FolderProblem.Offline)
        }
        try {
            val client = lease.client() ?: return FolderListing(DEFAULT_HOME, null, emptyList(), FolderProblem.Offline)
            // `cd -- "$1" && pwd` resolves `~` and symlinks; the folder travels as a positional argument, quoted.
            val resolved = client.machineExec(listOf("sh", "-lc", RESOLVE, "sh", "~"))
            if (resolved.code != 0) return FolderListing(DEFAULT_HOME, null, emptyList(), FolderProblem.Unreadable)
            return listOn(client, resolved.stdout.trim().ifEmpty { DEFAULT_HOME })
        } catch (e: TransportLost) {
            return FolderListing(DEFAULT_HOME, null, emptyList(), FolderProblem.Offline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HcException) {
            return FolderListing(DEFAULT_HOME, null, emptyList(), FolderProblem.Unreadable)
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    override suspend fun list(machineId: String, path: String): FolderListing {
        val lease = try {
            machines.acquire(machineId)
        } catch (e: NoSuchElementException) {
            return FolderListing(path, null, emptyList(), FolderProblem.Offline)
        }
        try {
            val client = lease.client() ?: return FolderListing(path, null, emptyList(), FolderProblem.Offline)
            return listOn(client, path)
        } catch (e: TransportLost) {
            return FolderListing(path, null, emptyList(), FolderProblem.Offline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HcException) {
            return FolderListing(path, null, emptyList(), FolderProblem.Unreadable)
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    private suspend fun listOn(client: io.github.werlang.whistl.core.rpc.HcClient, path: String): FolderListing {
        // `-p` marks the directories with a `/`, so the picker never offers a file as a folder.
        val listed = client.machineExec(listOf("ls", "-1Ap", "--", path))
        if (listed.code != 0) return FolderListing(path, parentOf(path), emptyList(), FolderProblem.Unreadable)
        val folders = listed.stdout.lineSequence()
            .filter { it.endsWith("/") }
            .map { it.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
        return FolderListing(path, parentOf(path), folders)
    }

    private fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        if (cut < 0) return null
        return if (cut == 0) "/" else trimmed.substring(0, cut)
    }

    private companion object {
        const val DEFAULT_HOME = "~"

        /** Fixed script; the folder arrives as `$1` and is always quoted. */
        const val RESOLVE = "cd -- \"$1\" 2>/dev/null && pwd"
    }
}
