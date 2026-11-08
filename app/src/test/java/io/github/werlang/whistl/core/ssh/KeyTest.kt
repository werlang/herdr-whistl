package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.support.FakeSecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Ed25519 client key, public-key line and fingerprint, against the RFC 8032 test 1 known answers (SPEC §6).
 */
class KeyTest {

    /** RFC 8032 test 1 secret key (seed). */
    private val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")

    /** RFC 8032 test 1 public key, from the same vector. */
    private val rfcPublic = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

    /**
     * The public line for the RFC secret matches the known answer exactly.
     */
    @Test
    fun publicLineForRfc8032Test1SecretMatchesKnownAnswer() {
        val secrets = FakeSecretStore()
        secrets.put(KeyManager.PRIVATE_KEY_ALIAS, rfcSeed)

        val line = KeyManager(secrets).publicKeyLine()

        assertEquals(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea",
            line,
        )
    }

    /**
     * The fingerprint of the RFC 8032 test 1 public key blob matches the known answer exactly.
     */
    @Test
    fun fingerprintOfRfc8032Test1KeyMatchesKnownAnswer() {
        val blob = SshWire.ed25519Blob(rfcPublic)

        assertEquals("SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8", SshFingerprint.of(blob))
    }

    /**
     * A freshly generated key yields a public line that parses back to a 32-byte Ed25519 key, and two generations
     * differ.
     */
    @Test
    fun freshKeyParsesBackToThirtyTwoBytesAndTwoGenerationsDiffer() {
        val first = KeyManager(FakeSecretStore()).apply { generate() }.publicKeyLine("whistl-test")
        val second = KeyManager(FakeSecretStore()).apply { generate() }.publicKeyLine("whistl-test")

        val parts = first.split(' ')
        assertEquals(3, parts.size)
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("whistl-test", parts[2])
        val blob = Base64.getDecoder().decode(parts[1])
        assertEquals(51, blob.size)
        assertArrayEquals(SshWire.string("ssh-ed25519"), blob.copyOfRange(0, 15))
        assertArrayEquals(byteArrayOf(0, 0, 0, 32), blob.copyOfRange(15, 19))
        assertEquals(32, blob.copyOfRange(19, blob.size).size)

        assertNotEquals(first, second)
    }

    /** `ensureKey` generates only when no key is stored; a second call keeps the same key. */
    @Test
    fun ensureKeyGeneratesOnlyWhenAbsent() {
        val manager = KeyManager(FakeSecretStore())

        assertTrue(manager.ensureKey())
        val line = manager.publicKeyLine()

        assertFalse(manager.ensureKey())
        assertEquals(line, manager.publicKeyLine())
    }

    /** The private seed never appears in `toString`. */
    @Test
    fun keyManagerToStringDoesNotShowTheSeed() {
        val secrets = FakeSecretStore()
        secrets.put(KeyManager.PRIVATE_KEY_ALIAS, rfcSeed)

        val text = KeyManager(secrets).toString()

        assertFalse(text.contains("9d61b19d"))
        assertFalse(text.contains(rfcSeed.joinToString("") { "%02x".format(it) }))
    }

    /** Asking for a public line with no key stored is a typed error with no key material in it. */
    @Test
    fun missingKeyIsATypedErrorWithoutKeyMaterial() {
        val error = assertThrows(ClientKeyMissing::class.java) {
            KeyManager(FakeSecretStore()).publicKeyLine()
        }

        assertFalse(error.message.orEmpty().contains("ssh"))
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
