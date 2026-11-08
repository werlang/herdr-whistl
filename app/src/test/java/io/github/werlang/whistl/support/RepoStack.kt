package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.core.ssh.PinnedHostKey
import io.github.werlang.whistl.core.ssh.SshWire
import io.github.werlang.whistl.data.AddResult
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.DefaultAgentsRepository
import io.github.werlang.whistl.data.DefaultMachinesRepository
import io.github.werlang.whistl.data.DefaultSpacesRepository
import io.github.werlang.whistl.data.DefaultTerminalRepository
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.DocumentChanges
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.NewMachine
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.spaceIdsFrom
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/**
 * The real machines, spaces and agents repositories wired to the fakes, on the test's background scope.
 *
 * Every machine's command channel is answered by [core], and every events channel by a snapshot of no terminals until a
 * test replaces [FakeMachineConnector.eventsPipeFactory].
 *
 * @param scope the test scope; its background scope runs every repository coroutine.
 * @param docChanges the document-change hub both repositories share; a test passes one whose announcement fails.
 */
class RepoStack(scope: TestScope, val docChanges: DocumentChanges = DocumentChanges()) {
    private var counter = 0
    private val random = Random(7)

    /** The fake transport. */
    val connector = FakeMachineConnector(scope.backgroundScope)

    /** The fake core's document and terminals. */
    val core = FakeCoreDoc()

    /** Saved passwords. */
    val secrets = FakeSecretStore()

    /** Pinned host keys. */
    val hostKeys = FakeHostKeyStore()

    /** The stored machine list. */
    val machineStore = InMemoryMachineStore()

    /** The last document read per machine. */
    val cache = InMemorySpacesCache()

    /** The resolved folder paths per machine. */
    val folderCache = InMemoryResolvedFolderCache()

    /** The machines repository under test. */
    val machines: MachinesRepository = DefaultMachinesRepository(
        scope = scope.backgroundScope,
        store = machineStore,
        secrets = secrets,
        keys = KeyManager(secrets),
        hostKeys = hostKeys,
        connector = connector,
        newId = { "m${++counter}" },
    )

    /** The spaces repository under test. */
    val spaces: SpacesRepository = DefaultSpacesRepository(
        scope = scope.backgroundScope,
        machines = machines,
        cache = cache,
        folderCache = folderCache,
        documentChanges = docChanges,
        ids = spaceIdsFrom { random.nextBytes(it) },
    )

    /** The agents repository under test. */
    val agents: AgentsRepository = DefaultAgentsRepository(
        scope = scope.backgroundScope,
        machines = machines,
        spaces = spaces,
        documentChanges = docChanges,
    )

    /** The terminal repository under test: the Agent screen's reads and actions. */
    val terminals: TerminalRepository = DefaultTerminalRepository(scope = scope.backgroundScope, machines = machines)

    init {
        connector.commandReply = core::handle
        connector.eventsPipeFactory = { snapshotPipe(eventsSnapshot(1, emptyList())) }
    }

    /**
     * Adds a machine whose host key is already pinned, as a machine the user has used before. Its connection starts as
     * soon as a repository holds a lease on it.
     *
     * @return the machine id.
     */
    suspend fun addMachine(): String {
        val id = (machines.add(NewMachine("", "mac.local", 22, "root")) as AddResult.Added).machine.id
        hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, connector.defaultKey))
        return id
    }

    /**
     * Sends one events-channel line to the newest subscription.
     *
     * @param line the notification line.
     */
    fun pushEvent(line: String) {
        connector.eventsPipes.last().push(line)
    }
}
