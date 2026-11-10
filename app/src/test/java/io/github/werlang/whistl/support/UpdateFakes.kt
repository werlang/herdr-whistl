package io.github.werlang.whistl.support

import io.github.werlang.whistl.data.DownloadOutcome
import io.github.werlang.whistl.data.UpdateCheck
import io.github.werlang.whistl.data.UpdateRelease
import io.github.werlang.whistl.data.UpdateRepository
import io.github.werlang.whistl.domain.AppVersion
import io.github.werlang.whistl.platform.AppInstaller
import java.io.File

/**
 * An update feed the test dictates: it answers with whatever it was given, and the download returns a file the test
 * created.
 *
 * @param currentVersion the version the app under test carries.
 */
class FakeUpdates(override val currentVersion: AppVersion = AppVersion(0, 3, 5)) : UpdateRepository {
    /** What `check()` answers. */
    var answer: UpdateCheck = UpdateCheck.UpToDate

    /** What `download()` answers. */
    var outcome: DownloadOutcome = DownloadOutcome.Failed(io.github.werlang.whistl.data.UpdateProblem.Network)

    /** How many times the feed was asked. */
    var checks: Int = 0

    /** The progress the download reported, before its outcome. */
    var progress: Pair<Long, Long> = 0L to 0L

    override suspend fun check(): UpdateCheck {
        checks++
        return answer
    }

    override suspend fun download(release: UpdateRelease, onProgress: (Long, Long) -> Unit): DownloadOutcome {
        onProgress(progress.first, progress.second)
        return outcome
    }
}

/**
 * An installer the test controls: it says whether installs are allowed and records what was installed.
 *
 * @param allowed whether the user has allowed installs.
 */
class FakeInstaller(var allowed: Boolean = true) : AppInstaller {
    /** The files handed to the installer. */
    val installed: MutableList<File> = mutableListOf()

    /** How many times the permission screen was asked for. */
    var permissionRequests: Int = 0

    /** What the installer answers to `install()`. */
    var accepts: Boolean = true

    /** The signers the downloaded APK "has". */
    var signers: Set<String> = setOf("aa")

    /** The signers the installed app "has". */
    var installedSignersList: Set<String> = setOf("aa")

    override fun signersOf(file: File): Set<String> = signers

    override fun installedSigners(): Set<String> = installedSignersList

    override fun canInstall(): Boolean = allowed

    override fun requestInstallPermission(): Boolean {
        permissionRequests++
        return true
    }

    override fun install(file: File): Boolean {
        installed += file
        return accepts
    }
}
