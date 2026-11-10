package io.github.werlang.whistl.ui.settings

import io.github.werlang.whistl.data.DownloadOutcome
import io.github.werlang.whistl.data.Refusal
import io.github.werlang.whistl.data.UpdateCheck
import io.github.werlang.whistl.data.UpdateProblem
import io.github.werlang.whistl.data.UpdateRelease
import io.github.werlang.whistl.domain.AppVersion
import io.github.werlang.whistl.support.FakeInstaller
import io.github.werlang.whistl.support.FakeUpdates
import io.github.werlang.whistl.support.InMemorySettingsStore
import io.github.werlang.whistl.support.MainDispatcherRule
import io.github.werlang.whistl.data.AppLanguage
import io.github.werlang.whistl.data.DefaultSettingsRepository
import io.github.werlang.whistl.data.FontSize
import io.github.werlang.whistl.data.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The update flow through the Settings view model (phase 9, SPEC §2.7): check, download, verify, install.
 *
 * The feed and the installer are fakes, so what is under test is the state machine and the fact that nothing is offered
 * for install before both checks passed.
 */
class UpdateFlowTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val release = UpdateRelease(
        version = AppVersion(0, 3, 6),
        apkUrl = "https://example.invalid/herdr-whistl-0.3.6.apk",
        sha256Url = "https://example.invalid/herdr-whistl-0.3.6.apk.sha256",
        bytes = 1_000L,
    )

    private class Harness(updates: FakeUpdates = FakeUpdates(), installer: FakeInstaller = FakeInstaller()) {
        val updates = updates
        val installer = installer
        val vm = SettingsViewModel(
            DefaultSettingsRepository(InMemorySettingsStore()),
            { _ -> },
            object : DemoControl {
                override val available = true
                override val enabled = false
                override fun setEnabled(on: Boolean) = Unit
            },
            versionName = "0.3.5",
            updates = updates,
            installer = installer,
        )
    }

    /** A newer release becomes a download button carrying the version. */
    @Test
    fun aNewerReleaseIsOffered() {
        val h = Harness()
        h.updates.answer = UpdateCheck.Available(release)

        h.vm.checkForUpdates()

        assertEquals(UpdateUiState.Available("0.3.6"), h.vm.update.value)
    }

    /** Being current says so, with the version that is installed. */
    @Test
    fun beingCurrentIsSaidPlainly() {
        val h = Harness()
        h.updates.answer = UpdateCheck.UpToDate

        h.vm.checkForUpdates()

        assertEquals(UpdateUiState.UpToDate("0.3.5"), h.vm.update.value)
    }

    /** A verified file becomes the install button, and installing hands exactly that file to the installer. */
    @Test
    fun aVerifiedFileIsInstalled() {
        val h = Harness()
        val file = File.createTempFile("herdr-whistl-0.3.6", ".apk")
        h.updates.answer = UpdateCheck.Available(release)
        h.updates.outcome = DownloadOutcome.Ready(file)

        h.vm.checkForUpdates()
        h.vm.downloadUpdate()

        assertEquals(UpdateUiState.Ready("0.3.6", file), h.vm.update.value)

        h.vm.installUpdate()

        assertEquals(listOf(file), h.installer.installed)
    }

    /** Without the system permission nothing is installed: the screen asks for it instead. */
    @Test
    fun installingWithoutPermissionAsksForIt() {
        val h = Harness()
        h.installer.allowed = false
        val file = File.createTempFile("herdr-whistl-0.3.6", ".apk")
        h.updates.answer = UpdateCheck.Available(release)
        h.updates.outcome = DownloadOutcome.Ready(file)

        h.vm.checkForUpdates()
        h.vm.downloadUpdate()
        h.vm.installUpdate()

        assertEquals(UpdateUiState.NeedsPermission, h.vm.update.value)
        assertEquals(1, h.installer.permissionRequests)
        assertTrue(h.installer.installed.isEmpty())
    }

    /** A checksum mismatch is reported as a refusal, and nothing reaches the installer. */
    @Test
    fun aChecksumMismatchIsReported() {
        val h = Harness()
        h.updates.answer = UpdateCheck.Available(release)
        h.updates.outcome = DownloadOutcome.Rejected(Refusal.Checksum)

        h.vm.checkForUpdates()
        h.vm.downloadUpdate()

        assertEquals(UpdateUiState.Refused(Refusal.Checksum), h.vm.update.value)
        assertTrue(h.installer.installed.isEmpty())
    }

    /** A signature mismatch is its own refusal: it means the file came from another key, not that it was corrupted. */
    @Test
    fun aSignatureMismatchIsItsOwnRefusal() {
        val h = Harness()
        h.updates.answer = UpdateCheck.Available(release)
        h.updates.outcome = DownloadOutcome.Rejected(Refusal.Signature)

        h.vm.checkForUpdates()
        h.vm.downloadUpdate()

        assertEquals(UpdateUiState.Refused(Refusal.Signature), h.vm.update.value)
    }

    /** A feed that cannot be reached says so instead of claiming the app is current. */
    @Test
    fun anUnreachableFeedIsNotUpToDate() {
        val h = Harness()
        h.updates.answer = UpdateCheck.Failed(UpdateProblem.Network)

        h.vm.checkForUpdates()

        assertEquals(UpdateUiState.Failed(UpdateProblem.Network), h.vm.update.value)
    }

    /** Downloading before a check offers nothing does nothing at all. */
    @Test
    fun downloadingWithoutAnOfferIsIgnored() {
        val h = Harness()

        h.vm.downloadUpdate()

        assertEquals(UpdateUiState.Idle, h.vm.update.value)
        assertEquals(0, h.updates.checks)
    }
}
