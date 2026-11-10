package io.github.werlang.whistl

import android.content.Context
import io.github.werlang.whistl.core.secure.KeystoreSecretStore
import io.github.werlang.whistl.core.ssh.CryptoProviders
import io.github.werlang.whistl.core.ssh.JavaSecurityRegistry
import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.core.ssh.SshMachineConnector
import io.github.werlang.whistl.data.AgentsRepository
import io.github.werlang.whistl.data.ClientPublicKey
import io.github.werlang.whistl.data.DefaultInstalledAgentsRepository
import io.github.werlang.whistl.data.InstalledAgentsRepository
import io.github.werlang.whistl.data.DefaultAgentsRepository
import io.github.werlang.whistl.data.DefaultMachinesRepository
import io.github.werlang.whistl.data.DefaultSettingsRepository
import io.github.werlang.whistl.data.DefaultSpacesRepository
import io.github.werlang.whistl.data.DemoCore
import io.github.werlang.whistl.data.DocumentChanges
import io.github.werlang.whistl.data.InstalledAgentsDetector
import io.github.werlang.whistl.data.MachinesRepository
import io.github.werlang.whistl.data.SettingsRepository
import io.github.werlang.whistl.data.SpacesRepository
import io.github.werlang.whistl.data.TerminalRepository
import io.github.werlang.whistl.data.DefaultTerminalRepository
import io.github.werlang.whistl.data.spaceIdsFrom
import io.github.werlang.whistl.demo.DemoCores
import io.github.werlang.whistl.platform.AndroidClock
import io.github.werlang.whistl.platform.SharedPreferencesHostKeyStore
import io.github.werlang.whistl.platform.SharedPreferencesMachineStore
import io.github.werlang.whistl.platform.SharedPreferencesResolvedFolderCache
import io.github.werlang.whistl.platform.SharedPreferencesSettingsStore
import io.github.werlang.whistl.platform.SharedPreferencesSpacesCache
import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.data.HttpUpdateRepository
import io.github.werlang.whistl.data.UpdateRepository
import io.github.werlang.whistl.domain.AppVersion
import io.github.werlang.whistl.platform.AndroidAppInstaller
import io.github.werlang.whistl.platform.AppInstaller
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.SecureRandom

/**
 * The app's object graph, built by hand (SPEC §4): one place that decides which implementation each repository uses.
 *
 * In demo mode a debug build gets the in-memory [DemoCore]; a release build has no demo core, so the real repositories
 * are used whatever the flag says. The real repositories are built lazily, so demo mode never starts them.
 *
 * Nothing constructs this yet: the first screen wires it in phase 4.
 *
 * @param context any context; the application context is kept.
 * @param demoMode `true` to run on the demo data when this build has it.
 */
class AppContainer(
    context: Context,
    demoMode: Boolean = false,
    demoCore: DemoCore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appExceptionHandler())
    private val demo: DemoCore? = demoCore ?: if (demoMode) DemoCores.fake() else null
    private val live: LiveCore by lazy { LiveCore(context.applicationContext, scope) }

    /** The machines the user added, and their connections. */
    val machines: MachinesRepository = demo?.machines ?: live.machines

    /** The agents on connected machines, and the blocked count. */
    val agents: AgentsRepository = demo?.agents ?: live.agents

    /** The spaces on each machine, and the edits to them. */
    val spaces: SpacesRepository = demo?.spaces ?: live.spaces

    /** The terminals the Agent screen reads and drives. */
    val terminals: TerminalRepository = demo?.terminals ?: live.terminals

    /** The app settings: language, font size, theme and the shortcut bar. */
    val settings: SettingsRepository = DefaultSettingsRepository(SharedPreferencesSettingsStore(context.applicationContext))

    /** The installed agents of each machine, from the detection that runs when a machine connects. */
    val installed: InstalledAgentsRepository = demo?.installed ?: live.installed

    /** This device's public key line, for the Add machine sheet. */
    val clientKey: ClientPublicKey = demo?.clientKey ?: live.clientKey

    /**
     * The machine the Spaces screen is filtered to, or `null` for every machine. Tapping a machine card sets it before
     * navigating, because Spaces is also a bottom destination with no arguments of its own (phase 9, SPEC §2.2).
     */
    val spacesFilter: MutableStateFlow<String?> = MutableStateFlow(null)

    /** The system side of an update: signatures, the install permission and the installer (SPEC §2.7). */
    val installer: AppInstaller = AndroidAppInstaller(context.applicationContext)

    /**
     * The release feed. It is the same in demo mode: an update is about this build, not about the demo data, and a
     * debug build keeps its own version so it never offers itself a release.
     */
    val updates: UpdateRepository = HttpUpdateRepository(
        currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0),
        installer = installer,
        cacheDir = context.applicationContext.cacheDir,
    )
}

/**
 * The client key's public line, from the Keystore-held key. The key is generated on first use, so the line exists before any
 * connection (SPEC §6).
 *
 * @param keys the app's client key.
 */
internal class KeyPublicKey(private val keys: KeyManager) : ClientPublicKey {
    override fun publicKeyLine(): String {
        keys.ensureKey()
        return keys.publicKeyLine(COMMENT)
    }

    private companion object {
        /** The comment at the end of the public line. */
        const val COMMENT = "whistl"
    }
}

/**
 * The real repositories and the stores behind them. Built once, on first use, only when the app is not on demo data.
 *
 * @param context the application context.
 * @param scope the scope the repositories run in.
 */
private class LiveCore(context: Context, scope: CoroutineScope) {
    init {
        // Before any SSH use: sshj needs the bundled BouncyCastle as its provider on every API level (SPEC §6, phase 7 r1c).
        CryptoProviders.install(JavaSecurityRegistry)
    }

    private val secrets = KeystoreSecretStore(context)
    private val keys = KeyManager(secrets)
    private val documentChanges = DocumentChanges()
    private val random = SecureRandom()

    val machines: MachinesRepository = DefaultMachinesRepository(
        scope = scope,
        store = SharedPreferencesMachineStore(context),
        secrets = secrets,
        keys = keys,
        hostKeys = SharedPreferencesHostKeyStore(context),
        connector = SshMachineConnector(scope),
    )

    val spaces: SpacesRepository = DefaultSpacesRepository(
        scope = scope,
        machines = machines,
        cache = SharedPreferencesSpacesCache(context),
        folderCache = SharedPreferencesResolvedFolderCache(context),
        documentChanges = documentChanges,
        ids = spaceIdsFrom { random.nextBytes(it) },
    )

    val agents: AgentsRepository = DefaultAgentsRepository(
        scope = scope,
        machines = machines,
        spaces = spaces,
        documentChanges = documentChanges,
    )

    val terminals: TerminalRepository = DefaultTerminalRepository(
        scope = scope,
        machines = machines,
        display = DefaultTerminalRepository.VIEWER_DISPLAY,
    )

    val installed: InstalledAgentsRepository = DefaultInstalledAgentsRepository(
        scope = scope,
        machines = machines,
        detector = InstalledAgentsDetector(AndroidClock),
    )

    val clientKey: ClientPublicKey = KeyPublicKey(keys)
}

/**
 * The last line of defence for the app's long-lived scope (phase 6 P0-2). A failure that nothing else caught ends only its own
 * coroutine, and is reported by its class name alone: one bad message from a core cannot end the process, and no message text
 * reaches the log.
 *
 * @param report receives the failure's class name. The default writes it to [Log].
 * @return the handler to install in the scope.
 */
internal fun appExceptionHandler(report: (String) -> Unit = { Log.w("AppScope", "uncaught $it") }): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error -> report(error::class.java.simpleName) }
