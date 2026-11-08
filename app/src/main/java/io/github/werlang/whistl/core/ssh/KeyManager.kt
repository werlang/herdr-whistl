package io.github.werlang.whistl.core.ssh

import io.github.werlang.whistl.core.secure.SecretStore
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import java.util.Base64

/** Thrown when a client key is needed but none is stored. Carries no key material. */
class ClientKeyMissing : RuntimeException("no client key is stored; generate one first")

/**
 * The app's SSH client key (SPEC §6): an Ed25519 key generated on the device.
 *
 * The 32-byte seed is the private key. It is stored only through [SecretStore]. Generation uses BouncyCastle's
 * lightweight Ed25519 API, not JCA `"Ed25519"`, because Android has that algorithm only from API 33 and minSdk is 26.
 *
 * The seed never appears in [toString], in exception messages, or in logs.
 *
 * @param secrets where the seed is stored.
 * @param random the entropy source for generation; defaults to [SecureRandom].
 */
class KeyManager(
    private val secrets: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Generates a key only when none is stored yet.
     *
     * @return `true` when a new key was generated, `false` when one already existed.
     */
    fun ensureKey(): Boolean {
        if (secrets.get(PRIVATE_KEY_ALIAS) != null) return false
        generate()
        return true
    }

    /** Generates a new Ed25519 key and replaces any stored one. */
    fun generate() {
        val key = Ed25519PrivateKeyParameters(random)
        secrets.put(PRIVATE_KEY_ALIAS, key.encoded)
    }

    /**
     * The OpenSSH public-key line for the stored key: `ssh-ed25519 <base64 blob> <comment>`.
     *
     * The line is public. It is what the user adds to `~/.ssh/authorized_keys` on the machine.
     *
     * @param comment an optional trailing comment; blank means no comment.
     * @return the line, without a trailing newline.
     * @throws ClientKeyMissing when no key is stored.
     */
    fun publicKeyLine(comment: String = ""): String {
        val seed = secrets.get(PRIVATE_KEY_ALIAS) ?: throw ClientKeyMissing()
        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val blob = SshWire.ed25519Blob(publicKey)
        val line = SshWire.ED25519 + " " + Base64.getEncoder().encodeToString(blob)
        return if (comment.isBlank()) line else line + " " + comment.trim()
    }

    /**
     * The stored 32-byte seed, for the SSH transport's authentication step. The caller must not log or keep it.
     *
     * @return a copy of the seed.
     * @throws ClientKeyMissing when no key is stored.
     */
    fun loadSeed(): ByteArray = (secrets.get(PRIVATE_KEY_ALIAS) ?: throw ClientKeyMissing()).copyOf()

    override fun toString(): String = "KeyManager(<key redacted>)"

    companion object {
        /** The alias the Ed25519 seed is stored under. */
        const val PRIVATE_KEY_ALIAS: String = "ssh.client.ed25519"
    }
}
