package io.github.werlang.whistl.core.secure

import android.security.keystore.KeyPermanentlyInvalidatedException
import io.github.werlang.whistl.core.ssh.KeyManager
import io.github.werlang.whistl.support.FakeWrappingCipher
import io.github.werlang.whistl.support.MapBlobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * The Keystore loss rules (phase 6 P0-4), on the JVM through [BlobStore] and [WrappingCipher] fakes. Each failure the Keystore can
 * raise is thrown in turn: a value that cannot be opened reads as null and is removed, and a dead key is discarded with it. Robolectric
 * runs the class because [KeyPermanentlyInvalidatedException] is an Android class.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreSecretStoreTest {

    private val blobs = MapBlobStore()
    private val cipher = FakeWrappingCipher()
    private val store = KeystoreSecretStore(blobs, cipher)

    /** A stored value comes back exactly; the blob on disk is not the plaintext. */
    @Test
    fun aValueRoundTripsThroughTheSealedBlob() {
        store.put("machine.password.m1", "hunter2".toByteArray())

        assertEquals("hunter2", String(store.get("machine.password.m1")!!))
        assertTrue("only the sealed form is kept", blobs.read("machine.password.m1") != "hunter2")
    }

    /** A value that was never stored reads as null, and nothing is removed or discarded. */
    @Test
    fun aMissingValueIsNullAndTouchesNothing() {
        assertNull(store.get("machine.password.m9"))

        assertEquals(0, blobs.removals)
        assertEquals(0, cipher.discards)
    }

    /** A blob that does not open under a healthy key is forgotten: the key stays, and the next put still works. */
    @Test
    fun aBlobThatDoesNotOpenIsForgottenAndTheKeyIsKept() {
        store.put("machine.password.m1", "hunter2".toByteArray())
        cipher.openFailure = AEADBadTagException("tag mismatch")

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(0, cipher.discards)
    }

    /** A key permanently invalidated (a lost key) reads as null, and the key is discarded so a new one can be made. */
    @Test
    fun aPermanentlyInvalidatedKeyIsDiscardedWithItsBlob() {
        store.put("machine.password.m1", "hunter2".toByteArray())
        cipher.openFailure = KeyPermanentlyInvalidatedException()

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(1, cipher.discards)
    }

    /** An unrecoverable key reads as null, and the key is discarded. */
    @Test
    fun anUnrecoverableKeyIsDiscardedWithItsBlob() {
        store.put("machine.password.m1", "hunter2".toByteArray())
        cipher.openFailure = UnrecoverableKeyException("key gone")

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(1, cipher.discards)
    }

    /** A Keystore error reads as null, and the key is discarded. */
    @Test
    fun aKeyStoreErrorIsDiscardedWithItsBlob() {
        store.put("machine.password.m1", "hunter2".toByteArray())
        cipher.openFailure = KeyStoreException("keystore unavailable")

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(1, cipher.discards)
    }

    /** A blob too short to hold an IV and a tag is forgotten without asking the cipher: nothing is padded and opened. */
    @Test
    fun aTruncatedBlobIsForgottenWithoutOpeningIt() {
        blobs.write("machine.password.m1", java.util.Base64.getEncoder().encodeToString(ByteArray(5)))

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(0, cipher.opens)
        assertEquals(0, cipher.discards)
    }

    /** A blob that is not base64 at all is forgotten and reads as null. */
    @Test
    fun aBlobThatIsNotBase64IsForgottenAndReadsAsNull() {
        blobs.write("machine.password.m1", "not base64 !!")

        assertNull(store.get("machine.password.m1"))

        assertNull(blobs.read("machine.password.m1"))
        assertEquals(0, cipher.discards)
    }

    /**
     * After the SSH key is lost, [KeyManager.ensureKey] generates a new one and the public key line is available again, so the
     * Add machine sheet can open (phase 6 P0-4).
     */
    @Test
    fun aLostSshKeyIsRegeneratedAndTheSheetHasAPublicKeyAgain() {
        blobs.write(KeyManager.PRIVATE_KEY_ALIAS, java.util.Base64.getEncoder().encodeToString(ByteArray(40)))
        cipher.openFailure = KeyPermanentlyInvalidatedException()
        val keys = KeyManager(store)

        assertTrue("a new key was generated", keys.ensureKey())
        assertTrue(keys.publicKeyLine().startsWith("ssh-ed25519 "))
    }
}
