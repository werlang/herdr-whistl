package io.github.werlang.whistl.core.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * SSH wire encoding (RFC 4251 `string`s) and the Ed25519 public-key blob (RFC 8709). Pure, no I/O.
 */
internal object SshWire {
    /** The key type name for Ed25519 keys. */
    const val ED25519: String = "ssh-ed25519"

    /**
     * Encodes bytes as an SSH `string`: a 4-byte big-endian length, then the bytes.
     *
     * @param bytes the bytes to encode.
     * @return the encoded string.
     */
    fun string(bytes: ByteArray): ByteArray {
        val length = bytes.size
        val out = ByteArray(4 + length)
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        bytes.copyInto(out, destinationOffset = 4)
        return out
    }

    /**
     * Encodes text as an SSH `string` (ASCII).
     *
     * @param text the text to encode.
     * @return the encoded string.
     */
    fun string(text: String): ByteArray = string(text.toByteArray(Charsets.US_ASCII))

    /**
     * The public-key blob for an Ed25519 key: `string("ssh-ed25519") || string(key)`. This is what an SSH server
     * sends, and what a fingerprint is computed over.
     *
     * @param publicKey the 32-byte Ed25519 public key.
     * @return the blob.
     * @throws IllegalArgumentException when the key is not 32 bytes.
     */
    fun ed25519Blob(publicKey: ByteArray): ByteArray {
        require(publicKey.size == ED25519_KEY_BYTES) { "an Ed25519 public key is 32 bytes" }
        return string(ED25519) + string(publicKey)
    }

    /** Length of an Ed25519 public key or seed, in bytes. */
    const val ED25519_KEY_BYTES: Int = 32
}

/**
 * OpenSSH-style SHA-256 fingerprints (SPEC §6): `SHA256:` followed by the unpadded base64 of SHA-256 over the key
 * blob, as `ssh-keygen -l` prints them.
 */
object SshFingerprint {
    /**
     * The fingerprint of a public-key blob.
     *
     * @param blob the SSH public-key blob (see [SshWire.ed25519Blob]).
     * @return for example `SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8`.
     */
    fun of(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
