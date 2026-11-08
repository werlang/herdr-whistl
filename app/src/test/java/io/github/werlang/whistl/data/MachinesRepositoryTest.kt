package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.core.secure.SecretStore
import io.github.werlang.whistl.core.ssh.ECONNREFUSED_ERRNO
import io.github.werlang.whistl.core.ssh.HostKeyStore
import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.core.ssh.PinnedHostKey
import io.github.werlang.whistl.core.ssh.SshFingerprint
import io.github.werlang.whistl.core.ssh.SshWire
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.support.FakeHostKeyStore
import io.github.werlang.whistl.support.FakeMachineConnector
import io.github.werlang.whistl.support.FakeMachineConnector.Attempt
import io.github.werlang.whistl.support.FakeSecretStore
import io.github.werlang.whistl.support.InMemoryMachineStore
import io.github.werlang.whistl.support.assertThrowsSuspend
import net.schmizz.sshj.common.SSHRuntimeException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connection lifecycle of [DefaultMachinesRepository] (SPEC §1, §2, §6), on virtual time. Each test records the
 * emitted states with an unconfined collector, so no intermediate state is lost to `StateFlow` conflation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachinesRepositoryTest {

    /** A repository wired to fakes, running its coroutines on the test's background scope. */
    internal class Harness(val scope: TestScope) {
        val connector = FakeMachineConnector(scope.backgroundScope)
        val store = InMemoryMachineStore()
        val secrets: SecretStore = FakeSecretStore()
        val hostKeys: HostKeyStore = FakeHostKeyStore()
        private var counter = 0

        /** What the repository reported for failures inside the app. Each entry must hold class names only. */
        val internalReports = mutableListOf<String>()
        val repo: MachinesRepository = DefaultMachinesRepository(
            scope = scope.backgroundScope,
            store = store,
            secrets = secrets,
            keys = KeyManager(secrets),
            hostKeys = hostKeys,
            connector = connector,
            newId = { "m${++counter}" },
            errnoOf = { if (it is ConnectException) ECONNREFUSED_ERRNO else null },
            reportInternal = { internalReports += it },
        )

        /**
         * Adds the test machine. By default its host key is already pinned to the connector's default key, the way a
         * machine the user has used before looks; pass `pinned = false` to start from trust on first use.
         */
        suspend fun addHomelab(pinned: Boolean = true): String {
            val id = (repo.add(NewMachine("", "homelab.example", 22, "root")) as AddResult.Added).machine.id
            if (pinned) hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, connector.defaultKey))
            return id
        }

        /** Records every state [id] emits from now on. */
        fun record(id: String): MutableList<ConnectionState> {
            val seen = mutableListOf<ConnectionState>()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                repo.observe(id).collect { seen += it }
            }
            return seen
        }

        /** Sessions open right now: opened minus closed. */
        fun openSessions(): Int = connector.sessionsOpened - connector.sessionsClosed
    }

    /** Short names for a state sequence, so assertions read as the transition they describe. */
    private fun List<ConnectionState>.kinds(): List<String> = map {
        when (it) {
            ConnectionState.Offline -> "Offline"
            ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Error -> "Error(${it.kind})"
            is ConnectionState.HostKeyUnknown -> "HostKeyUnknown"
            is ConnectionState.HostKeyChanged -> "HostKeyChanged"
        }
    }

    /** offline → connecting → connected, with the core's hello in the state. */
    @Test
    fun connectsFromOfflineThroughConnectingToConnected() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val seen = h.record(id)

        val lease = h.repo.acquire(id)
        runCurrent()

        assertEquals(listOf("Offline", "Connecting", "Connected"), seen.kinds())
        assertEquals("0.1.18", (seen.last() as ConnectionState.Connected).hello.version)
        assertEquals(1, h.connector.connectCalls)
        assertNotNull(lease.client())
    }

    /**
     * The login works but herdr-core is not installed for it (phase 7 r3): `Error(CoreMissing)`, not Offline. Before the fix the
     * empty hc answer read as "could not reach the machine" and retried forever.
     */
    @Test
    fun aMissingCoreIsErrorAndIsNotRetriedAutomatically() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Fails(io.github.werlang.whistl.core.rpc.CoreMissing()))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(CoreMissing)"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Error(CoreMissing)"), seen.kinds())
    }

    /**
     * A failure inside the app (here an `IllegalStateException`) is `Error(Internal)`: not Offline, and not retried, since a
     * retry would repeat it (phase 7 r1c). Before the fix it was `Offline` with a retry, which read as "could not reach".
     */
    @Test
    fun internalFailureIsErrorAndIsNotRetriedAutomatically() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Fails(IllegalStateException("provider broke")))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(Internal)"), seen.kinds())
        assertEquals(listOf("connection failed inside the app: IllegalStateException"), h.internalReports)

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Error(Internal)"), seen.kinds())
    }

    /** The failure sshj raises for a missing key-exchange algorithm, as the transport wraps it, is still `Error(Internal)`. */
    @Test
    fun providerFailureWrappedByTheTransportIsInternal() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Fails(TransportLost("ssh connection or login failed", sshjProviderFailure())))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(Internal)"), seen.kinds())
        assertEquals(1, h.connector.connectCalls)
        assertEquals(
            listOf("connection failed inside the app: TransportLost <- IOException <- SSHRuntimeException <- NoSuchAlgorithmException"),
            h.internalReports,
        )
    }

    /** Nothing listens on the port (`ECONNREFUSED`): `Error(ConnectionRefused)`, not retried automatically. */
    @Test
    fun refusedPortIsErrorAndIsNotRetriedAutomatically() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Fails(ConnectException("refused")))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(ConnectionRefused)"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
    }

    /** A network failure (here an unknown host) stays `Offline` and retries on its own, as before. */
    @Test
    fun networkFailureIsOfflineAndRetriesOnItsOwn() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Fails(UnknownHostException("homelab")))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Offline"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(2, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Offline", "Connecting", "Connected"), seen.kinds())
    }

    /** The chain sshj produces when the key exchange asks the `BC` provider for a missing algorithm (phase 7 r1c). */
    private fun sshjProviderFailure(): Throwable = IOException("key exchange failed").apply {
        initCause(SSHRuntimeException("key exchange failed").apply { initCause(NoSuchAlgorithmException("X25519")) })
    }

    /** A refused login is `Error(AuthFailed)`; the connector is not called again, even after a long virtual delay. */
    @Test
    fun authFailureIsErrorAndIsNotRetriedAutomatically() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.AuthRefused)
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(AuthFailed)"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Error(AuthFailed)"), seen.kinds())
    }

    /**
     * A password machine whose saved password can no longer be opened reads as absent: the attempt ends in `Error(AuthFailed)` before
     * any connect, and the connector is not called again after a long virtual delay (phase 6 P0-4).
     */
    @Test
    fun aPasswordMachineWhoseSecretIsGoneIsAuthFailedAndNeverRetried() = runTest {
        val h = Harness(this)
        val added = h.repo.add(NewMachine("web", "homelab.example", 22, "root"), password = "hunter2".toCharArray()) as AddResult.Added
        h.hostKeys.pin(added.machine.id, PinnedHostKey(SshWire.ED25519, h.connector.defaultKey))
        // What KeystoreSecretStore.get leaves behind when the blob does not open: nothing under the alias.
        h.secrets.delete("machine.password.${added.machine.id}")
        val seen = h.record(added.machine.id)

        h.repo.acquire(added.machine.id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(AuthFailed)"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(0, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Error(AuthFailed)"), seen.kinds())
    }

    /**
     * A changed host key blocks: the state is `HostKeyChanged`, the session never reaches `hello`, nothing is pinned
     * over the old key, nothing retries after a long delay, and `confirmHostKey` is refused.
     */
    @Test
    fun hostKeyChangedBlocksNeverConnectsNeverPinsNeverRetries() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val pinned = SshWire.ed25519Blob(ByteArray(32) { 9 })
        h.hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, pinned))
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "HostKeyChanged"), seen.kinds())
        assertEquals(
            ConnectionState.HostKeyChanged(
                SshWire.ED25519,
                SshFingerprint.of(pinned),
                SshFingerprint.of(h.connector.defaultKey),
            ),
            seen.last(),
        )
        assertEquals(0, h.connector.helloCalls)

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)

        assertEquals(ConfirmResult.Blocked, h.repo.confirmHostKey(id))
        runCurrent()
        assertEquals(PinnedHostKey(SshWire.ED25519, pinned), h.hostKeys.pinned(id))
        assertEquals(listOf("Offline", "Connecting", "HostKeyChanged"), seen.kinds())
    }

    /**
     * An unknown host key waits for the user. `confirmHostKey` pins exactly the presented key and then connects.
     */
    @Test
    fun hostKeyUnknownWaitsForConfirmationThenPinsAndConnects() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "HostKeyUnknown"), seen.kinds())
        assertEquals(
            ConnectionState.HostKeyUnknown(SshWire.ED25519, SshFingerprint.of(h.connector.defaultKey)),
            seen.last(),
        )
        assertNull(h.hostKeys.pinned(id))

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)

        assertEquals(ConfirmResult.Trusted, h.repo.confirmHostKey(id))
        runCurrent()
        assertEquals(PinnedHostKey(SshWire.ED25519, h.connector.defaultKey), h.hostKeys.pinned(id))
        assertEquals(
            listOf("Offline", "Connecting", "HostKeyUnknown", "Connecting", "Connected"),
            seen.kinds(),
        )
        assertEquals(2, h.connector.connectCalls)
    }

    /**
     * A session lost mid-session goes offline, and the retry runs exactly at the first backoff step (1 s), not before.
     */
    @Test
    fun transportLostMidSessionGoesOfflineAndRetriesAtTheFirstBackoffStep() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val seen = h.record(id)
        h.repo.acquire(id)
        runCurrent()

        h.connector.lastSession!!.kill(TransportLost("events watchdog"))
        runCurrent()
        assertEquals("Offline", seen.kinds().last())

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, h.connector.connectCalls)
        assertEquals(
            listOf("Offline", "Connecting", "Connected", "Offline", "Connecting", "Connected"),
            seen.kinds(),
        )
    }

    /**
     * Failures to open back off 1 s, 2 s, then 4 s, and the third try succeeds. Each step is checked at its boundary.
     */
    @Test
    fun failedOpensBackOffDoublingFromOneSecond() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(
            FakeMachineConnector.Attempt.Unreachable,
            FakeMachineConnector.Attempt.Unreachable,
            FakeMachineConnector.Attempt.Healthy,
        )
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, h.connector.connectCalls)

        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(2, h.connector.connectCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, h.connector.connectCalls)
        assertEquals("Connected", seen.kinds().last())
    }

    /** `UnsupportedGeneration` from `hello` is `Error(UnsupportedGeneration)`, and it is not retried. */
    @Test
    fun generationTwoIsAnErrorAndIsNotRetried() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.GenerationTwo)
        val seen = h.record(id)

        h.repo.acquire(id)
        runCurrent()
        assertEquals(listOf("Offline", "Connecting", "Error(UnsupportedGeneration)"), seen.kinds())

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        assertEquals(0, h.connector.sessionsOpened)
    }

    /** A machine can be confirmed only while a host key waits for it; otherwise nothing is pinned. */
    @Test
    fun confirmWithNothingPendingPinsNothing() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)

        assertEquals(ConfirmResult.NothingPending, h.repo.confirmHostKey(id))
        assertNull(h.hostKeys.pinned(id))
    }

    /** Removing a machine closes its session and deletes its saved password and its pinned host key. */
    @Test
    fun removeClosesTheSessionAndDeletesSecretAndPin() = runTest {
        val h = Harness(this)
        val draft = h.repo.add(NewMachine("", "homelab.example", 22, "root"), password = "hunter2".toCharArray())
        val id = (draft as AddResult.Added).machine.id
        h.hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, h.connector.defaultKey))
        assertNotNull(h.secrets.get(DefaultMachinesRepository.passwordAlias(id)))
        h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.openSessions())

        h.repo.remove(id)

        assertEquals(0, h.openSessions())
        assertEquals(1, h.connector.sessionsClosed)
        assertNull(h.secrets.get(DefaultMachinesRepository.passwordAlias(id)))
        assertNull(h.hostKeys.pinned(id))
        assertFalse(h.repo.machines().value.any { it.id == id })
    }

    /** Validation rejects each bad field with its typed reason, and nothing is stored. */
    @Test
    fun addRejectsBadFieldsWithTypedReasons() = runTest {
        val h = Harness(this)

        assertEquals(
            AddResult.Rejected(MachineField.Host, Rejection.BAD_FORMAT),
            h.repo.add(NewMachine("", "-bad.example", 22, "root")),
        )
        assertEquals(
            AddResult.Rejected(MachineField.Port, Rejection.OUT_OF_RANGE),
            h.repo.add(NewMachine("", "homelab.example", 0, "root")),
        )
        assertEquals(
            AddResult.Rejected(MachineField.Port, Rejection.OUT_OF_RANGE),
            h.repo.add(NewMachine("", "homelab.example", 65536, "root")),
        )
        assertEquals(
            AddResult.Rejected(MachineField.User, Rejection.BAD_CHARACTERS),
            h.repo.add(NewMachine("", "homelab.example", 22, "evil;rm -rf")),
        )
        assertEquals(
            AddResult.Rejected(MachineField.Password, Rejection.EMPTY),
            h.repo.add(NewMachine("", "homelab.example", 22, "root"), password = CharArray(0)),
        )
        assertTrue(h.repo.machines().value.isEmpty())
        assertTrue(h.store.list().isEmpty())
    }

    /** A blank name defaults to the first label of the host (SPEC §1). */
    @Test
    fun blankNameDefaultsToTheFirstHostLabel() = runTest {
        val h = Harness(this)

        val added = h.repo.add(NewMachine("  ", "homelab.example", 22, "root")) as AddResult.Added

        assertEquals("homelab", added.machine.name)
    }

    /**
     * A saved password goes only through `SecretStore`, under the documented alias. The stored machine record holds
     * no secret, and the password value round-trips exactly.
     */
    @Test
    fun savedPasswordIsStoredOnlyThroughSecretStore() = runTest {
        val h = Harness(this)

        val added = h.repo.add(NewMachine("web", "homelab.example", 22, "root"), password = "hunter2".toCharArray())
            as AddResult.Added

        assertEquals(
            "hunter2",
            String(h.secrets.get("machine.password.${added.machine.id}")!!, Charsets.UTF_8),
        )
        val stored = h.store.list().single()
        assertEquals(AuthMethod.Password, stored.auth)
        assertEquals(added.machine, stored.machine)
    }

    /** A machine added without a password logs in with the client key, and stores no password at all. */
    @Test
    fun keyLoginStoresNoPassword() = runTest {
        val h = Harness(this)

        val added = h.repo.add(NewMachine("web", "homelab.example", 22, "root")) as AddResult.Added

        assertNull(h.secrets.get("machine.password.${added.machine.id}"))
        assertEquals(AuthMethod.Key, h.store.list().single().auth)
    }

    /** An empty host is rejected as the host field, with [Rejection.EMPTY]. */
    @Test
    fun emptyHostIsRejectedAsTheHostField() = runTest {
        val h = Harness(this)

        assertEquals(
            AddResult.Rejected(MachineField.Host, Rejection.EMPTY),
            h.repo.add(NewMachine("web", "", 22, "root")),
        )
    }

    /** Silence after an acquire and no failure: the lifecycle stays where it is. */
    @Test
    fun noEventsAreEmittedWithoutAChange() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val seen = h.record(id)

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(listOf("Offline"), seen.kinds())
        assertEquals(0, h.connector.connectCalls)
    }

    // ---- Edit machine (phase 7 P2). Rules R1–R4 are in MachinesRepository.edit; each has its own test below. ----

    /** R1: a name-only edit is stored in place; the pinned key and the live session are not touched. */
    @Test
    fun r1NameOnlyKeepsThePinnedKeyAndTheSession() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.repo.acquire(id)
        runCurrent()
        val pinned = h.hostKeys.pinned(id)
        val opened = h.connector.sessionsOpened

        val result = h.repo.edit(id, NewMachine("renamed", "homelab.example", 22, "root"), AuthMethod.Key)

        assertEquals("renamed", (result as EditResult.Saved).machine.name)
        assertEquals("renamed", h.repo.machines().value.single().name)
        assertSame(pinned, h.hostKeys.pinned(id))
        assertEquals(opened, h.connector.sessionsOpened)
        assertEquals(0, h.connector.sessionsClosed)
        assertEquals(1, h.connector.connectCalls)
        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)
    }

    /** R2: a user or port change keeps the pinned key, closes the session and reconnects with the new settings. */
    @Test
    fun r2UserOrPortChangeKeepsThePinnedKeyAndReconnects() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.repo.acquire(id)
        runCurrent()
        val pinned = h.hostKeys.pinned(id)

        h.repo.edit(id, NewMachine("", "homelab.example", 2222, "admin"), AuthMethod.Key)
        runCurrent()

        assertSame(pinned, h.hostKeys.pinned(id))
        assertEquals(1, h.connector.sessionsClosed)
        assertEquals(2, h.connector.connectCalls)
        assertEquals(2222, h.repo.machines().value.single().port)
        assertEquals("admin", h.repo.machines().value.single().user)
        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)
    }

    /**
     * P7R8 item 1: a key that is pending on the old port is forgotten when the port changes. The new port presents its own key,
     * and that is the key the card offers and Trust pins.
     */
    @Test
    fun aPendingKeyIsForgottenWhenThePortChanges() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        h.repo.acquire(id)
        runCurrent()
        assertEquals(
            ConnectionState.HostKeyUnknown(SshWire.ED25519, SshFingerprint.of(h.connector.defaultKey)),
            h.repo.observe(id).value,
        )

        val newPortKey = SshWire.ed25519Blob(ByteArray(32) { 2 })
        // The restart and the connection after Trust both present the new port's key.
        h.connector.script(Attempt.PresentKey(newPortKey), Attempt.PresentKey(newPortKey))
        h.repo.edit(id, NewMachine("", "homelab.example", 2222, "root"), AuthMethod.Key)
        runCurrent()

        assertEquals(
            ConnectionState.HostKeyUnknown(SshWire.ED25519, SshFingerprint.of(newPortKey)),
            h.repo.observe(id).value,
        )
        assertNull(h.hostKeys.pinned(id))
        assertEquals(ConfirmResult.Trusted, h.repo.confirmHostKey(id))
        runCurrent()
        assertEquals(PinnedHostKey(SshWire.ED25519, newPortKey), h.hostKeys.pinned(id))
        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)
    }

    /**
     * P7R8 item 1: a changed key stays blocked when the port changes. The restart compares the new port's key with the pin: a
     * different key is blocked again, and nothing is re-pinned.
     */
    @Test
    fun aChangedKeyStaysBlockedAfterAPortChangeAndIsNeverRepinned() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        val pinned = SshWire.ed25519Blob(ByteArray(32) { 9 })
        h.hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, pinned))
        h.repo.acquire(id)
        runCurrent()
        assertTrue(h.repo.observe(id).value is ConnectionState.HostKeyChanged)
        val connectsBefore = h.connector.connectCalls

        h.repo.edit(id, NewMachine("", "homelab.example", 2222, "root"), AuthMethod.Key)
        runCurrent()

        assertEquals(connectsBefore + 1, h.connector.connectCalls)
        assertEquals(
            ConnectionState.HostKeyChanged(
                SshWire.ED25519,
                SshFingerprint.of(pinned),
                SshFingerprint.of(h.connector.defaultKey),
            ),
            h.repo.observe(id).value,
        )
        assertEquals(0, h.connector.helloCalls)
        assertEquals(PinnedHostKey(SshWire.ED25519, pinned), h.hostKeys.pinned(id))
    }

    /** P7R8 item 1: a changed key on the old port connects on the new port when the new port presents the pinned key. */
    @Test
    fun aChangedKeyClearsWhenTheNewPortPresentsThePinnedKey() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        val pinned = SshWire.ed25519Blob(ByteArray(32) { 9 })
        h.hostKeys.pin(id, PinnedHostKey(SshWire.ED25519, pinned))
        h.repo.acquire(id)
        runCurrent()
        assertTrue(h.repo.observe(id).value is ConnectionState.HostKeyChanged)

        h.connector.script(Attempt.PresentKey(pinned))
        h.repo.edit(id, NewMachine("", "homelab.example", 2222, "root"), AuthMethod.Key)
        runCurrent()

        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)
        assertEquals(PinnedHostKey(SshWire.ED25519, pinned), h.hostKeys.pinned(id))
    }

    /**
     * P7R8 item 1: a pending key is kept when only the user changes. The same port presents the same key, so no restart runs
     * and Trust still pins the key that was shown.
     */
    @Test
    fun aPendingKeyStaysPendingWhenOnlyTheUserChanges() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        h.repo.acquire(id)
        runCurrent()
        val connectsBefore = h.connector.connectCalls

        h.repo.edit(id, NewMachine("", "homelab.example", 22, "admin"), AuthMethod.Key)
        runCurrent()

        assertEquals(connectsBefore, h.connector.connectCalls)
        assertEquals(
            ConnectionState.HostKeyUnknown(SshWire.ED25519, SshFingerprint.of(h.connector.defaultKey)),
            h.repo.observe(id).value,
        )
        assertEquals(ConfirmResult.Trusted, h.repo.confirmHostKey(id))
        runCurrent()
        assertEquals(PinnedHostKey(SshWire.ED25519, h.connector.defaultKey), h.hostKeys.pinned(id))
    }

    /** R2: a login-method change to password reconnects and keeps the pinned key; the saved password is stored. */
    @Test
    fun r2LoginMethodChangeToPasswordReconnectsAndKeepsThePin() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.repo.acquire(id)
        runCurrent()
        val pinned = h.hostKeys.pinned(id)

        h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Password, "secret".toCharArray())
        runCurrent()

        assertSame(pinned, h.hostKeys.pinned(id))
        assertEquals(2, h.connector.connectCalls)
        assertEquals(AuthMethod.Password, h.repo.authOf(id))
        assertEquals("secret", String(h.secrets.get(DefaultMachinesRepository.passwordAlias(id))!!))
    }

    /** R3: a host change forgets the pin, and the next connection shows the new host's key for confirmation. */
    @Test
    fun r3HostChangeForgetsThePinAndRequiresFirstConfirmation() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val newKey = SshWire.ed25519Blob(ByteArray(32) { 9 })
        h.repo.acquire(id)
        runCurrent()
        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)

        // The new host presents its own key on the next connection.
        h.connector.script(FakeMachineConnector.Attempt.PresentKey(newKey))
        h.repo.edit(id, NewMachine("", "new.example", 22, "root"), AuthMethod.Key)
        runCurrent()

        assertNull(h.hostKeys.pinned(id))
        val unknown = h.repo.observe(id).value as ConnectionState.HostKeyUnknown
        assertEquals(SshFingerprint.of(newKey), unknown.fingerprint)

        // After Trust the same host presents the same key again: the connection goes through.
        h.connector.script(FakeMachineConnector.Attempt.PresentKey(newKey))
        assertEquals(ConfirmResult.Trusted, h.repo.confirmHostKey(id))
        runCurrent()
        assertTrue(h.repo.observe(id).value is ConnectionState.Connected)
        assertTrue(h.hostKeys.pinned(id)!!.blob.contentEquals(newKey))
    }

    /**
     * R3, cancelled: the confirmation is not given. The machine keeps the new host, has no pinned key, and has no session. It
     * stays in the host-key state until the user trusts the key, or removes the machine (the decision stated in the report).
     */
    @Test
    fun r3CancelledConfirmationLeavesTheNewHostWithoutAPinOrASession() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.repo.acquire(id)
        runCurrent()

        h.connector.script(FakeMachineConnector.Attempt.PresentKey(SshWire.ed25519Blob(ByteArray(32) { 9 })))
        h.repo.edit(id, NewMachine("", "new.example", 22, "root"), AuthMethod.Key)
        runCurrent()

        assertEquals("new.example", h.repo.machines().value.single().host)
        assertNull(h.hostKeys.pinned(id))
        assertEquals(0, h.openSessions())
        assertTrue(h.repo.observe(id).value is ConnectionState.HostKeyUnknown)
    }

    /** R4: an edit keeps the machine's id and the machine count, so its spaces (keyed by id) stay with it. */
    @Test
    fun r4EditKeepsTheIdAndTheMachineCount() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.repo.acquire(id)
        runCurrent()

        h.repo.edit(id, NewMachine("box", "other.example", 22, "root"), AuthMethod.Key)
        runCurrent()

        assertEquals(1, h.repo.machines().value.size)
        assertEquals(id, h.repo.machines().value.single().id)
    }

    /** A rejected edit changes nothing: the stored machine, the pin and the connections stay as they were. */
    @Test
    fun aRejectedEditChangesNothing() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val before = h.repo.machines().value.single()

        val result = h.repo.edit(id, NewMachine("", "", 22, "root"), AuthMethod.Key)

        assertEquals(EditResult.Rejected(MachineField.Host, Rejection.EMPTY), result)
        assertEquals(before, h.repo.machines().value.single())
        assertEquals(0, h.connector.connectCalls)
    }

    /** Switching to password login with no password, and none saved, is rejected; a blank password on a saved one keeps it. */
    @Test
    fun aPasswordIsRequiredOnlyWhenNoneIsSaved() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()

        val needsOne = h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Password, null)
        assertEquals(EditResult.Rejected(MachineField.Password, Rejection.EMPTY), needsOne)
        assertEquals(AuthMethod.Key, h.repo.authOf(id))

        h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Password, "first".toCharArray())
        val kept = h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Password, null)
        assertEquals(EditResult.Saved(h.repo.machines().value.single()), kept)
        assertEquals("first", String(h.secrets.get(DefaultMachinesRepository.passwordAlias(id))!!))

        h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Password, "second".toCharArray())
        assertEquals("second", String(h.secrets.get(DefaultMachinesRepository.passwordAlias(id))!!))

        h.repo.edit(id, NewMachine("", "homelab.example", 22, "root"), AuthMethod.Key, null)
        assertNull(h.secrets.get(DefaultMachinesRepository.passwordAlias(id)))
    }

    /**
     * P2 follow-up: an attempt to the old host is in flight (held inside connect, before its host key is checked) when an edit
     * changes the host. The old attempt then completes with a refusal carrying the old host's key. That refusal must not reach
     * the slot: the state never shows the old key, and confirming the new host pins the new key, never the old one.
     */
    @Test
    fun aStaleRefusalFromTheOldHostCannotLeakItsKeyIntoTheNewHost() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = false)
        val oldKey = SshWire.ed25519Blob(ByteArray(32) { 7 })
        val newKey = SshWire.ed25519Blob(ByteArray(32) { 9 })
        val release = CompletableDeferred<Unit>()
        h.connector.script(
            FakeMachineConnector.Attempt.Suspended(release, FakeMachineConnector.Attempt.PresentKey(oldKey), beforeHostKey = true),
            FakeMachineConnector.Attempt.PresentKey(newKey),
        )
        val seen = h.record(id)
        h.repo.acquire(id)
        runCurrent()

        h.repo.edit(id, NewMachine("", "new.example", 22, "root"), AuthMethod.Key)
        runCurrent()
        assertEquals(SshFingerprint.of(newKey), (h.repo.observe(id).value as ConnectionState.HostKeyUnknown).fingerprint)

        release.complete(Unit)
        runCurrent()

        val oldFingerprint = SshFingerprint.of(oldKey)
        assertTrue("the old key was shown: ${seen.kinds()}", seen.none { it is ConnectionState.HostKeyUnknown && it.fingerprint == oldFingerprint })
        assertEquals(SshFingerprint.of(newKey), (h.repo.observe(id).value as ConnectionState.HostKeyUnknown).fingerprint)
        h.connector.script(FakeMachineConnector.Attempt.PresentKey(newKey))
        assertEquals(ConfirmResult.Trusted, h.repo.confirmHostKey(id))
        runCurrent()
        assertTrue(h.hostKeys.pinned(id)!!.blob.contentEquals(newKey))
    }

    /**
     * P2 follow-up, the late success: an attempt to the old host passes its host-key check against the old pin, is held before its
     * session opens, and the edit then changes the host. When the old attempt completes, its session must be closed, not adopted:
     * the slot stays on the new host's state, and no session stays open.
     */
    @Test
    fun aLateSuccessOfTheOldHostIsClosedNotAdopted() = runTest {
        val h = Harness(this)
        val id = h.addHomelab(pinned = true)
        val newKey = SshWire.ed25519Blob(ByteArray(32) { 9 })
        val release = CompletableDeferred<Unit>()
        h.connector.script(
            FakeMachineConnector.Attempt.Suspended(release, FakeMachineConnector.Attempt.Healthy, beforeHostKey = false),
            FakeMachineConnector.Attempt.PresentKey(newKey),
        )
        h.repo.acquire(id)
        runCurrent()

        h.repo.edit(id, NewMachine("", "new.example", 22, "root"), AuthMethod.Key)
        runCurrent()
        assertTrue(h.repo.observe(id).value is ConnectionState.HostKeyUnknown)

        release.complete(Unit)
        runCurrent()

        assertTrue("the old session was adopted: ${h.repo.observe(id).value}", h.repo.observe(id).value is ConnectionState.HostKeyUnknown)
        assertEquals(0, h.openSessions())
        assertNull(h.hostKeys.pinned(id))
    }

    /**
     * Reconnect on a connected machine restarts the session (phase 7 r5 amendment U2): the old session closes, the state goes
     * Connecting and then Connected again, and one new connection is made. Before the fix Reconnect was a no-op here.
     */
    @Test
    fun reconnectWhileConnectedClosesTheSessionAndConnectsAgain() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        val lease = h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        val seen = h.record(id)

        h.repo.reconnect(id)
        runCurrent()

        assertEquals(2, h.connector.connectCalls)
        assertEquals(1, h.connector.sessionsClosed)
        assertEquals(1, h.openSessions())
        assertEquals(listOf("Connected", "Connecting", "Connected"), seen.kinds())
        assertNotNull(lease.client())
    }

    /**
     * Reconnect while offline makes one immediate attempt, not a wait for the next backoff (phase 7 r5 amendment U2): the
     * scheduled retry is replaced, and the attempt runs without advancing virtual time.
     */
    @Test
    fun reconnectWhileOfflineMakesOneImmediateAttempt() = runTest {
        val h = Harness(this)
        val id = h.addHomelab()
        h.connector.script(FakeMachineConnector.Attempt.Unreachable)
        val lease = h.repo.acquire(id)
        runCurrent()
        assertEquals(1, h.connector.connectCalls)
        val seen = h.record(id)

        h.repo.reconnect(id)
        runCurrent()

        assertEquals(2, h.connector.connectCalls)
        assertEquals(listOf("Offline", "Connecting", "Connected"), seen.kinds())
        assertNotNull(lease.client())
    }
}
