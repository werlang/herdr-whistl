package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.rpc.AuthFailed
import io.github.werlang.whistl.core.rpc.TransportLost
import io.github.werlang.whistl.support.FakeHostKeyStore
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase-2 seams this round extends (round p3r1 §2.1, §2.2): a refused login is a distinct typed error, an unknown
 * host key carries what trusting needs, and a changed key never does.
 */
class SeamTest {

    private val alg = SshWire.ED25519
    private val blob = SshWire.ed25519Blob(ByteArray(32) { 3 })

    /** sshj's user-auth refusal maps to [AuthFailed]: not retried, and not a transport loss. */
    @Test
    fun userAuthRefusalMapsToAuthFailed() {
        val mapped = openFailure(UserAuthException("refused by server"))

        assertTrue(mapped is AuthFailed)
        assertFalse(mapped.message.orEmpty().contains("server"))
    }

    /** Every other failure to open stays a [TransportLost]. */
    @Test
    fun otherOpenFailuresStayTransportLost() {
        val mapped = openFailure(TransportException("socket reset"))

        assertTrue(mapped is TransportLost)
    }

    /** For an unknown key the gate keeps the presented key, so the refusal can carry it for pinning. */
    @Test
    fun unknownKeyIsCarriedOnTheRefusal() {
        val gate = HostKeyGate(HostKeyPolicy(FakeHostKeyStore()), "m1")

        assertFalse(gate.admit(alg, blob))
        val refused = HostKeyRefused(gate.lastDecision!!, gate.lastPresented)

        assertTrue(refused.decision is HostKeyDecision.Unknown)
        assertEquals(alg, refused.presented!!.algorithm)
        assertTrue(blob.contentEquals(refused.presented!!.blob))
    }

    /** For a changed key the presented key is never handed back: the refusal refuses to carry it. */
    @Test
    fun changedKeyIsNeverCarriedOnTheRefusal() {
        val store = FakeHostKeyStore()
        HostKeyPolicy(store).trust("m1", alg, SshWire.ed25519Blob(ByteArray(32) { 7 }))
        val gate = HostKeyGate(HostKeyPolicy(store), "m1")

        assertFalse(gate.admit(alg, blob))
        assertNull(gate.lastPresented)
        assertTrue(gate.lastDecision is HostKeyDecision.Changed)
        assertThrows(IllegalArgumentException::class.java) {
            HostKeyRefused(gate.lastDecision!!, PresentedHostKey(alg, blob))
        }
    }

    /** A trusted key is admitted and carries no presented key. */
    @Test
    fun trustedKeyIsAdmittedWithNoPresentedKey() {
        val store = FakeHostKeyStore()
        HostKeyPolicy(store).trust("m1", alg, blob)
        val gate = HostKeyGate(HostKeyPolicy(store), "m1")

        assertTrue(gate.admit(alg, blob))
        assertNull(gate.lastPresented)
    }

    /** Forgetting a machine drops its pin, so the next key is unknown again. */
    @Test
    fun forgetDropsThePin() {
        val store = FakeHostKeyStore()
        val policy = HostKeyPolicy(store)
        policy.trust("m1", alg, blob)

        store.forget("m1")

        assertTrue(policy.check("m1", alg, blob) is HostKeyDecision.Unknown)
    }
}
