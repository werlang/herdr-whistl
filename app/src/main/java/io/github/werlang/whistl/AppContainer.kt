package io.github.werlang.whistl

import android.content.Context
import android.os.Build
import io.github.werlang.whistl.core.secure.KeystoreSecretStore
import io.github.werlang.whistl.core.ssh.CryptoProviders
import io.github.werlang.whistl.core.ssh.JavaSecurityRegistry
import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.core.ssh.SshMachineConnector
import io.github.werlang.whistl.data.AgentsRepository
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
import io.github.werlang.whistl.data.spaceIdsFrom
import io.github.werlang.whistl.demo.DemoCores
import io.github.werlang.whistl.platform.AndroidClock
import io.github.werlang.whistl.platform.SharedPreferencesHostKeyStore
import io.github.werlang.whistl.platform.SharedPreferencesMachineStore
import io.github.werlang.whistl.platform.SharedPreferencesSettingsStore
import io.github.werlang.whistl.platform.SharedPreferencesSpacesCache
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
class AppContainer(context: Context, demoMode: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val demo: DemoCore? = if (demoMode) DemoCores.fake() else null
    private val live: LiveCore by lazy { LiveCore(context.applicationContext, scope) }

    /** The machines the user added, and their connections. */
    val machines: MachinesRepository = demo?.machines ?: live.machines

    /** The agents on connected machines, and the blocked count. */
    val agents: AgentsRepository = demo?.agents ?: live.agents

    /** The spaces on each machine, and the edits to them. */
    val spaces: SpacesRepository = demo?.spaces ?: live.spaces

    /** The app settings: language, font size, theme and the shortcut bar. */
    val settings: SettingsRepository = DefaultSettingsRepository(SharedPreferencesSettingsStore(context.applicationContext))

    /** The known agents installed on a machine, cached for one minute per machine. */
    val installedAgents: InstalledAgentsDetector = InstalledAgentsDetector(AndroidClock)
}

/**
 * The real repositories and the stores behind them. Built once, on first use, only when the app is not on demo data.
 *
 * @param context the application context.
 * @param scope the scope the repositories run in.
 */
private class LiveCore(context: Context, scope: CoroutineScope) {
    init {
        // Before any SSH use: below API 33 sshj needs BouncyCastle as its provider (SPEC §6, phase 4 decision 2).
        CryptoProviders.install(Build.VERSION.SDK_INT, JavaSecurityRegistry)
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
        documentChanges = documentChanges,
        ids = spaceIdsFrom { random.nextBytes(it) },
    )

    val agents: AgentsRepository = DefaultAgentsRepository(
        scope = scope,
        machines = machines,
        spaces = spaces,
        documentChanges = documentChanges,
    )
}
