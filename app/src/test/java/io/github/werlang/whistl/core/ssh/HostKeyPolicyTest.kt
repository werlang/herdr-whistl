package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.support.FakeHostKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust on first use (SPEC §6): first use needs confirmation, the same key is trusted, a changed key is refused and
 * never re-pinned, and machines do not share pins.
 */
class HostKeyPolicyTest {

    /** A stand-in Ed25519 key blob for the machine's host key. */
    private val hostKey = SshWire.ed25519Blob(
        ByteArray(32) { i -> (0xd7 + i).toByte() },
    )

    /** A different key, for the changed-key cases. */
    private val otherKey = SshWire.ed25519Blob(ByteArray(32) { 7 })

    private val alg = SshWire.ED25519

    /** First use: the presented key is unknown, the fingerprint is shown, and nothing is pinned by checking. */
    @Test
    fun firstUseIsUnknownWithFingerprintAndCheckingPinsNothing() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)

        val decision = policy.check("m1", alg, hostKey)

        assertEquals(HostKeyDecision.Unknown(alg, SshFingerprint.of(hostKey)), decision)
        assertNull(store.pinned("m1"))
    }

    /** Pinning happens only through [HostKeyPolicy.trust], after confirmation; then the same key is trusted. */
    @Test
    fun trustPinsOnlyWhenCalledAndThenTheSameKeyIsTrusted() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)

        assertEquals(HostKeyDecision.Trusted, policy.trust("m1", alg, hostKey))

        assertEquals(PinnedHostKey(alg, hostKey), store.pinned("m1"))
        assertEquals(HostKeyDecision.Trusted, policy.check("m1", alg, hostKey))
    }

    /**
     * A different key is [HostKeyDecision.Changed] with both fingerprints. Trusting it does not re-pin: the original
     * key stays trusted and the new one stays refused.
     */
    @Test
    fun changedKeyIsRefusedAndNeverRepinned() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)
        policy.trust("m1", alg, hostKey)

        val changed = policy.check("m1", alg, otherKey)
        assertEquals(
            HostKeyDecision.Changed(alg, SshFingerprint.of(hostKey), SshFingerprint.of(otherKey)),
            changed,
        )

        assertEquals(changed, policy.trust("m1", alg, otherKey))
        assertEquals(PinnedHostKey(alg, hostKey), store.pinned("m1"))
        assertEquals(HostKeyDecision.Trusted, policy.check("m1", alg, hostKey))
    }

    /** The same blob under another algorithm name is a changed key, not a match. */
    @Test
    fun algorithmChangeIsAChangedKey() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)
        policy.trust("m1", alg, hostKey)

        val decision = policy.check("m1", "ecdsa-sha2-nistp256", hostKey)

        assertTrue(decision is HostKeyDecision.Changed)
    }

    /** Pins are per machine: trusting one machine does not trust another. */
    @Test
    fun machinesDoNotShareAPin() {
        val policy = HostKeyPolicy(FakeHostKeyStore())
        policy.trust("m1", alg, hostKey)

        assertEquals(HostKeyDecision.Trusted, policy.check("m1", alg, hostKey))
        assertEquals(HostKeyDecision.Unknown(alg, SshFingerprint.of(hostKey)), policy.check("m2", alg, hostKey))
    }

    /** The verifier gate admits only a trusted key, records the decision, and never pins. */
    @Test
    fun gateAdmitsOnlyTrustedKeysAndNeverPins() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)
        val gate = HostKeyGate(policy, "m1")

        assertFalse(gate.admit(alg, hostKey))
        assertTrue(gate.lastDecision is HostKeyDecision.Unknown)
        assertNull(store.pinned("m1"))

        policy.trust("m1", alg, hostKey)
        assertTrue(gate.admit(alg, hostKey))
        assertEquals(HostKeyDecision.Trusted, gate.lastDecision)

        assertFalse(gate.admit(alg, otherKey))
        assertTrue(gate.lastDecision is HostKeyDecision.Changed)
        assertEquals(PinnedHostKey(alg, hostKey), store.pinned("m1"))
    }

    /** The gate offers the pinned algorithm to SSH, and nothing when no pin exists. */
    @Test
    fun gateOffersOnlyThePinnedAlgorithm() {
        val policy = HostKeyPolicy(FakeHostKeyStore())
        val gate = HostKeyGate(policy, "m1")
        assertEquals(emptyList<String>(), gate.pinnedAlgorithms())

        policy.trust("m1", alg, hostKey)

        assertEquals(listOf(alg), gate.pinnedAlgorithms())
    }

    /** The refusal exception carries the decision, and its message names neither host nor key. */
    @Test
    fun refusalCarriesTheDecisionWithoutKeyOrHostInTheMessage() {
        val decision = HostKeyDecision.Changed(alg, "SHA256:old", "SHA256:new")

        val refused = HostKeyRefused(decision)

        assertEquals(decision, refused.decision)
        assertFalse(refused.message.orEmpty().contains("SHA256"))
    }
}
