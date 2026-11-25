package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.Conflict
import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.HcException
import io.github.werlang.whistl.core.rpc.NotFound
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.domain.TerminalState
import io.github.werlang.whistl.domain.Validation
import io.github.werlang.whistl.domain.validateFolder
import io.github.werlang.whistl.domain.validateSpaceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The document every core keeps its spaces in (SPEC §5). */
const val WHISTL_DOCUMENT: String = "whistl"

/** The folder a new space gets when none is typed (SPEC §1). */
const val DEFAULT_FOLDER: String = "~"

/** Write attempts for one space change before a conflict is reported (see [docReadModifyWrite]). */
private const val MAX_ATTEMPTS: Int = 5

/**
 * One machine's spaces, in display order (SPEC §1: pinned first, then order).
 *
 * @property machineId the machine.
 * @property offline `true` when the machine is not connected: the spaces come from the cache.
 * @property spaces the spaces, sorted for display.
 * @property resolutions each space's folder resolution, keyed by space id (phase 4 decision 1). A space with no entry has
 *   not been resolved on this device yet: its machine has never connected with that folder. An [FolderResolution.Unresolvable]
 *   entry is the typed error the space row shows.
 * @property tabSpaces the space each Herdr desktop tab belongs to, keyed by tab id (0.7.3). Agents whose `herdr.tab` label
 *   names one of these resolve to the owning space. Empty until the desktop's document has been read.
 * @property tabNames the desktop's own tab names, keyed by tab id. A terminal's `herdr.tab` gets its name here before any
 *   `where` fallback; the map is per machine, like [tabSpaces].
 * @property desktopSpaceNames the raw `name` the desktop's document gives each space id. It is the context for a `where`
 *   prefix after the user renaming the Whistl space, because the Herdr text keeps the desktop name.
 */
data class MachineSpaces(
    val machineId: String,
    val offline: Boolean,
    val spaces: List<SpaceRecord>,
    val resolutions: Map<String, FolderResolution> = emptyMap(),
    val tabSpaces: Map<String, String> = emptyMap(),
    val tabNames: Map<String, String> = emptyMap(),
    val desktopSpaceNames: Map<String, String> = emptyMap(),
) {
    /**
     * The resolution of a space's folder.
     *
     * @param space a space of this machine.
     * @return the resolution, or `null` when the folder has not been resolved yet.
     */
    fun resolutionOf(space: SpaceRecord): FolderResolution? = resolutions[space.id]

    /**
     * The absolute path a space's folder resolved to. Agents are matched by cwd against this path.
     *
     * @param space a space of this machine.
     * @return the resolved path, or `null` when it is unknown or unresolvable.
     */
    fun resolvedPathOf(space: SpaceRecord): String? = (resolutionOf(space) as? FolderResolution.Resolved)?.path
}

/** Which field of a space edit was rejected. */
enum class SpaceField {
    /** The folder path. */
    Folder,

    /** The space name. */
    Name,
}

/** The outcome of a space edit. Invalid input and missing targets are values, not exceptions. */
sealed interface SpaceResult {
    /**
     * The edit was written.
     *
     * @property spaceId the space the edit concerns (the new id, for a create).
     */
    data class Done(val spaceId: String) : SpaceResult

    /**
     * The input was rejected. Nothing was written.
     *
     * @property field the field that failed.
     * @property reason why.
     */
    data class Rejected(val field: SpaceField, val reason: Rejection) : SpaceResult

    /**
     * No space with that id is in the machine's document.
     *
     * @property spaceId the id that was looked for.
     */
    data class UnknownSpace(val spaceId: String) : SpaceResult

    /** The machine is not connected, so nothing can be written. */
    data object MachineOffline : SpaceResult
}

/**
 * The spaces on each machine, and the edits to them (SPEC §1, §5). Every write goes through the `whistl` document's
 * compare-and-set loop, so a concurrent writer's change survives.
 */
interface SpacesRepository {
    /**
     * Spaces per machine, in machine order.
     *
     * @return a state flow of the groups.
     */
    fun groups(): StateFlow<List<MachineSpaces>>

    /**
     * Creates a space. The id is new; the folder defaults to `~`; a blank name defaults to the folder's last segment.
     *
     * @param machineId the machine.
     * @param folder the folder on the machine.
     * @param name the display name, or blank for the default.
     * @return the new space's id, or the rejected field.
     */
    suspend fun create(machineId: String, folder: String = DEFAULT_FOLDER, name: String = ""): SpaceResult

    /**
     * Renames a space.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @param name the new name.
     * @return the result of the edit.
     */
    suspend fun rename(machineId: String, spaceId: String, name: String): SpaceResult

    /**
     * Pins or unpins a space.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @param pinned the new pin state.
     * @return the result of the edit.
     */
    suspend fun setPinned(machineId: String, spaceId: String, pinned: Boolean): SpaceResult

    /**
     * Moves a space one place up within its pinned group. At the top of the group nothing changes.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @return the result of the edit.
     */
    suspend fun moveUp(machineId: String, spaceId: String): SpaceResult

    /**
     * Moves a space one place down within its pinned group. At the bottom of the group nothing changes.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @return the result of the edit.
     */
    suspend fun moveDown(machineId: String, spaceId: String): SpaceResult

    /**
     * Closes a space: kills the live agents that match it, then removes it from the document.
     *
     * @param machineId the machine.
     * @param spaceId the space.
     * @return the result of the edit.
     */
    suspend fun close(machineId: String, spaceId: String): SpaceResult
}

/**
 * The default [SpacesRepository] (round p3r2).
 *
 * The `whistl` document of each machine is read when the machine connects, and again when the events channel reports a
 * `doc` change for it. The last copy is kept in [SpacesCache], so an offline machine still shows its spaces, flagged
 * offline. Writes run through [docReadModifyWrite] with the machine's live client.
 *
 * On each connection the folders of the spaces are resolved (see [FolderResolver]) in a job of their own, so a slow probe
 * never holds up the connection state. A folder's verdict is kept for the life of the app; while none exists, a path
 * resolved earlier and kept in [folderCache] is used. The resolved paths are never written to the document.
 *
 * @param scope the scope for watchers and resolution jobs.
 * @param machines the machines and their leases.
 * @param cache the cache of the last document per machine.
 * @param folderCache the cache of resolved folder paths per machine, kept on this device only.
 * @param documentChanges where `doc` changes arrive.
 * @param ids the source of new space ids.
 */
class DefaultSpacesRepository(
    private val scope: CoroutineScope,
    private val machines: MachinesRepository,
    private val cache: SpacesCache,
    private val folderCache: ResolvedFolderCache,
    private val documentChanges: DocumentChanges,
    private val ids: SpaceIdSource,
) : SpacesRepository {
    private val documents = ConcurrentHashMap<String, SpacesDocument>()
    private val online = ConcurrentHashMap<String, Boolean>()
    private val leases = ConcurrentHashMap<String, MachineLease>()
    private val watchers = ConcurrentHashMap<String, Job>()
    private val view = MutableStateFlow<List<MachineSpaces>>(emptyList())

    /** The latest verdict per machine and folder, from the connection that produced it. */
    private val verdicts = ConcurrentHashMap<String, ConcurrentHashMap<String, FolderResolution>>()

    /** The folder resolver of each live connection, by machine. Removed when the machine goes offline. */
    private val resolvers = ConcurrentHashMap<String, FolderResolver>()

    /** The resolution job of each machine, cancelled when its connection ends or a newer one replaces it. */
    private val resolving = ConcurrentHashMap<String, Job>()

    /**
     * The desktop's tabs and spaces per machine, from one successful read of [HERDR_DOCUMENT]. Publishing both maps from this
     * one snapshot keeps an owner and a name from the same revision.
     */
    private val desktopReads = ConcurrentHashMap<String, DesktopRead>()

    /** One refresh at a time per machine: a connection refresh and a doc-triggered one must not overwrite each other. */
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    /** One coherent desktop read: the tabs by id, and the spaces by id (the first entry of a duplicate id). */
    private data class DesktopRead(val tabs: Map<String, DesktopTab>, val spaces: Map<String, DesktopSpace>)

    override fun groups(): StateFlow<List<MachineSpaces>> = view.asStateFlow()

    init {
        scope.launch {
            machines.machines().collect { list -> reconcile(list.map { it.id }.toSet()) }
        }
        scope.launch {
            documentChanges.changes.collect { id -> if (online[id] == true) refresh(id) }
        }
    }

    override suspend fun create(machineId: String, folder: String, name: String): SpaceResult {
        val path = when (val r = validateFolder(folder.ifBlank { DEFAULT_FOLDER })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Folder, r.reason)
        }
        val spaceName = when (val r = validateSpaceName(name.ifBlank { defaultSpaceName(path) })) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Name, r.reason)
        }
        val id = ids.next()
        val written = write(machineId) { doc ->
            doc.withSpace(SpaceRecord(id = id, name = spaceName, folder = path, pinned = false, order = doc.nextOrder()))
        }
        if (!written) return SpaceResult.MachineOffline
        // The new folder is resolved now, on this connection, not when the core's doc event comes back (phase 4 r2, F4).
        resolveFolders(machineId)
        return SpaceResult.Done(id)
    }

    override suspend fun rename(machineId: String, spaceId: String, name: String): SpaceResult {
        val spaceName = when (val r = validateSpaceName(name)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> return SpaceResult.Rejected(SpaceField.Name, r.reason)
        }
        // A deliberate name stops following the desktop document: the explicit marker is what a later refresh and a restart
        // read, so the absence of herdrName cannot make this look like an old adopted record.
        return change(machineId, spaceId) {
            it.mapSpace(spaceId) { space -> space.copy(name = spaceName, herdrName = null, nameOverride = true) }
        }
    }

    override suspend fun setPinned(machineId: String, spaceId: String, pinned: Boolean): SpaceResult =
        change(machineId, spaceId) { it.mapSpace(spaceId) { space -> space.copy(pinned = pinned) } }

    override suspend fun moveUp(machineId: String, spaceId: String): SpaceResult =
        change(machineId, spaceId) { it.moved(spaceId, up = true) }

    override suspend fun moveDown(machineId: String, spaceId: String): SpaceResult =
        change(machineId, spaceId) { it.moved(spaceId, up = false) }

    override suspend fun close(machineId: String, spaceId: String): SpaceResult {
        if (documents[machineId]?.hasSpace(spaceId) == false) return SpaceResult.UnknownSpace(spaceId)
        val client = leases[machineId]?.client() ?: return SpaceResult.MachineOffline
        val known = documents[machineId]?.spaces.orEmpty()
        val knownTabs = desktopReads[machineId]?.tabs.orEmpty().mapValues { (_, tab) -> tab.spaceId }
        try {
            for (terminal in client.terminalList().terminals) {
                val match = matchSpace(
                    terminal,
                    known,
                    { resolutionOf(machineId, it.folder).resolvedPath() },
                    { tab -> knownTabs[tab] },
                )
                if (terminal.state == TerminalState.Live && match?.id == spaceId) {
                    try {
                        client.terminalKill(terminal.address)
                    } catch (e: NotFound) {
                        // Already gone: nothing to kill.
                    }
                }
            }
        } catch (e: TransportLost) {
            return SpaceResult.MachineOffline
        }
        return change(machineId, spaceId) { it.without(spaceId) }
    }

    /** Applies one edit to an existing space, after checking that the space is there. */
    private suspend fun change(machineId: String, spaceId: String, transform: (SpacesDocument) -> SpacesDocument): SpaceResult {
        if (documents[machineId]?.hasSpace(spaceId) == false) return SpaceResult.UnknownSpace(spaceId)
        return if (write(machineId, transform)) SpaceResult.Done(spaceId) else SpaceResult.MachineOffline
    }

    /**
     * Runs [change] through the compare-and-set loop on the machine's live client. Returns `false` when the machine is
     * not connected or the connection drops during the write.
     */
    private suspend fun write(machineId: String, change: (SpacesDocument) -> SpacesDocument): Boolean {
        val client = leases[machineId]?.client() ?: return false
        val written = try {
            docReadModifyWrite(client, WHISTL_DOCUMENT, MAX_ATTEMPTS, change)
        } catch (e: TransportLost) {
            return false
        }
        remember(machineId, written)
        return true
    }

    /** Starts a watcher for each new machine, and stops the watcher of each machine that was removed. */
    private suspend fun reconcile(current: Set<String>) {
        for (id in watchers.keys.toList()) {
            if (id !in current) {
                watchers.remove(id)?.cancelAndJoin()
                documents.remove(id)
                online.remove(id)
                verdicts.remove(id)
                cache.remove(id)
                folderCache.remove(id)
                desktopReads.remove(id)
                refreshLocks.remove(id)
            }
        }
        for (id in current) {
            if (!watchers.containsKey(id)) {
                documents.putIfAbsent(id, cache.get(id) ?: SpacesDocument.empty())
                watchers[id] = scope.launch { watch(id) }
            }
        }
        publish()
    }

    /** Follows one machine's connection: reads its document when it connects, and marks it offline otherwise. */
    private suspend fun watch(id: String) {
        val lease = try {
            machines.acquire(id)
        } catch (e: NoSuchElementException) {
            return
        }
        leases[id] = lease
        try {
            machines.observe(id).collect { state ->
                val connected = state is ConnectionState.Connected
                online[id] = connected
                if (connected) startConnection(id) else endConnection(id)
                publish()
                if (connected) refresh(id)
            }
        } catch (e: NoSuchElementException) {
            return
        } finally {
            withContext(NonCancellable) {
                endConnection(id)
                leases.remove(id)
                online.remove(id)
                lease.release()
                publish()
            }
        }
    }

    /** A connection has opened: its folder resolver, so each folder is probed once on this connection. */
    private fun startConnection(id: String) {
        val client = leases[id]?.client() ?: return
        resolvers[id] = FolderResolver(id, client, folderCache)
    }

    /** The connection has ended: its resolution job stops, and its resolver is dropped. */
    private fun endConnection(id: String) {
        resolving.remove(id)?.cancel()
        resolvers.remove(id)
    }

    /**
     * Reads the document from the live client and keeps it. A read that fails keeps the copy already held. Refreshes of one
     * machine are serialized: a connection refresh and a doc-triggered refresh can arrive together, and the older desktop
     * read must not win the compare-and-set of the newer one.
     */
    private suspend fun refresh(id: String) {
        refreshLocks.getOrPut(id) { Mutex() }.withLock { refreshLocked(id) }
    }

    /** The body of [refresh], already serialized per machine. */
    private suspend fun refreshLocked(id: String) {
        val client = leases[id]?.client() ?: return
        val read = try {
            client.docGet(WHISTL_DOCUMENT)
        } catch (e: HcException) {
            return
        }
        val doc = try {
            SpacesDocument.fromJson(read.value)
        } catch (e: DocumentException) {
            return
        }
        remember(id, adoptDesktopSpaces(id, client, doc, read.version))
        resolveFolders(id)
    }

    /**
     * The document with the Herdr desktop's spaces added (0.7.3), and the desktop's tab map kept for the machine.
     *
     * The desktop keeps its spaces in [HERDR_DOCUMENT] on the same core, and labels the terminals it starts with
     * `herdr.space` and those ids, and with `herdr.tab` and the id of the tab they run in. Adopting the missing ones, id
     * included, is what makes a space made on the desktop usable here without retyping the folder — and makes its agents
     * resolve to it by label or tab instead of by cwd. Its spaces are deduplicated by id only: the desktop may keep
     * several spaces on the same folder, and each is a space of its own.
     *
     * The space's name is the desktop's `name`; its folder is optional and may be blank. The tabs are kept with their ids,
     * their owning space and their names. An adopted space's name and folder follow later desktop changes until the user
     * renames it here ([syncDesktopSpaces]); a Rename stops only the name, never the folder.
     *
     * A desktop document that cannot be read leaves the document and the tab map as they are. A desktop document with
     * nothing new still refreshes the tab map and names, so a terminal can resolve by tab even when no space is adopted. A
     * write that fails still shows the adopted spaces: they come back on the next refresh.
     *
     * @param machineId the machine.
     * @param client the machine's live client.
     * @param doc the document this app holds.
     * @param initialVersion the version of [WHISTL_DOCUMENT] that [doc] was read from.
     * @return the document to keep.
     */
    private suspend fun adoptDesktopSpaces(
        machineId: String,
        client: HcClient,
        doc: SpacesDocument,
        initialVersion: Long,
    ): SpacesDocument {
        var base = doc
        var version = initialVersion
        var attempts = 0
        while (true) {
            val desktopValue = try {
                client.docGet(HERDR_DOCUMENT).value
            } catch (e: HcException) {
                // A document that cannot be read keeps the last tab map and names; a blank folder on a stale map is no worse than
                // no name at all.
                return base
            }
            val tabs = parseDesktopTabs(desktopValue)
            val desktop = parseDesktopSpaces(desktopValue)
            desktopReads[machineId] = DesktopRead(tabs, indexDesktopSpaces(desktop))
            val synced = syncDesktopSpaces(base.spaces, desktop)
            val missing = adoptedSpaces(synced, desktop)
            if (synced == base.spaces && missing.isEmpty()) return base
            val merged = SpacesDocument(synced + missing, base.unknownFields)
            try {
                client.docSet(WHISTL_DOCUMENT, merged.toJson(), version)
                return merged
            } catch (e: Conflict) {
                attempts++
                if (attempts >= MAX_ATTEMPTS) return merged
                // Re-read whistl first so the next attempt reads herdr against the latest whistl version.
                val read = try {
                    client.docGet(WHISTL_DOCUMENT)
                } catch (e: HcException) {
                    return merged
                }
                base = try {
                    SpacesDocument.fromJson(read.value)
                } catch (e: DocumentException) {
                    return merged
                }
                version = read.version
            } catch (e: HcException) {
                return merged
            }
        }
    }

    /**
     * Resolves the folders of the machine's spaces on its live connection, in a job of its own, so a slow probe never holds
     * up the connection state. A newer call replaces the job still running. The resolver keeps each folder's result, so a
     * folder is probed once per connection.
     *
     * @param id the machine.
     */
    private fun resolveFolders(id: String) {
        val resolver = resolvers[id] ?: return
        resolving.remove(id)?.cancel()
        resolving[id] = scope.launch {
            // A blank folder is not probed: there is no path to check, and the space stays usable by label and tab.
            val folders = documents[id]?.spaces.orEmpty().map { it.folder }.filter { it.isNotBlank() }.distinct()
            for (folder in folders) {
                val verdict = try {
                    resolver.resolve(folder)
                } catch (e: TransportLost) {
                    return@launch
                }
                verdicts.getOrPut(id) { ConcurrentHashMap() }[folder] = verdict
                publish()
            }
        }
    }

    /**
     * The resolution of a folder on a machine: the latest verdict from a connection, else the path kept on this device for
     * it, else `null`.
     *
     * @param machineId the machine.
     * @param folder the folder as typed.
     * @return the resolution, or `null` when none is known.
     */
    private fun resolutionOf(machineId: String, folder: String): FolderResolution? =
        verdicts[machineId]?.get(folder) ?: folderCache.get(machineId, folder)?.let { FolderResolution.Resolved(it) }

    private fun remember(id: String, doc: SpacesDocument) {
        documents[id] = doc
        cache.put(id, doc)
        publish()
    }

    private fun publish() {
        view.value = machines.machines().value.map { machine ->
            val spaces = sortSpaces(documents[machine.id]?.spaces.orEmpty())
            val desktop = desktopReads[machine.id]
            val tabs = desktop?.tabs.orEmpty()
            MachineSpaces(
                machineId = machine.id,
                offline = online[machine.id] != true,
                spaces = spaces,
                resolutions = spaces.filter { it.folder.isNotBlank() }.mapNotNull { space ->
                    resolutionOf(machine.id, space.folder)?.let { space.id to it }
                }.toMap(),
                tabSpaces = tabs.mapValues { (_, tab) -> tab.spaceId },
                tabNames = tabs.mapNotNull { (id, tab) -> tab.name?.let { id to it } }.toMap(),
                desktopSpaceNames = desktop?.spaces.orEmpty().mapValues { (_, space) -> space.name },
            )
        }
    }
}

/**
 * The resolved path inside a resolution, or `null` for no resolution or an unresolvable folder.
 *
 * @return the path, or `null`.
 */
private fun FolderResolution?.resolvedPath(): String? = (this as? FolderResolution.Resolved)?.path
