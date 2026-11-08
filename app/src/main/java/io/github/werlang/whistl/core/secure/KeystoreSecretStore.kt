package io.github.werlang.whistl.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] backed by the Android Keystore (SPEC §6).
 *
 * Each value is sealed with AES-256-GCM under one Keystore key. That key is non-exportable and is created on first
 * use. Keystore picks a random 12-byte IV for every seal; the IV is stored in front of the ciphertext, and the whole
 * blob is kept base64-encoded in private SharedPreferences. Only ciphertext reaches disk.
 *
 * A value that cannot be opened is forgotten (phase 6 P0-4): [get] returns `null`, and the blob is removed, so the caller
 * sees the value as never stored. When the failure says the Keystore key itself is dead (permanently invalidated, unrecoverable,
 * or a Keystore error), the key is discarded too, so the next [put] creates a new one. Nothing here throws for a lost secret.
 *
 * The Android calls sit behind [BlobStore] and [WrappingCipher], so the recovery rules are tested on the JVM.
 *
 * @param context any context; the application context is kept.
 */
class KeystoreSecretStore internal constructor(
    private val blobs: BlobStore,
    private val cipher: WrappingCipher,
) : SecretStore {

    constructor(context: Context) : this(
        SharedPreferencesBlobs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        AndroidWrappingCipher(),
    )

    override fun put(alias: String, value: ByteArray) {
        blobs.write(alias, Base64.getEncoder().encodeToString(cipher.seal(value)))
    }

    /**
     * Reads and opens the value under [alias]. A blob that is not base64, is too short to hold an IV and a tag, or does not open
     * is forgotten and reads as `null`. A dead key is discarded with it.
     *
     * @param alias the name the value is stored under.
     * @return the secret bytes, or `null` when nothing is stored or it could not be opened.
     */
    override fun get(alias: String): ByteArray? {
        val encoded = blobs.read(alias) ?: return null
        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return forget(alias, keyDead = false)
        }
        if (blob.size <= IV_BYTES + TAG_BYTES) return forget(alias, keyDead = false)
        return try {
            cipher.open(blob)
        } catch (e: KeyPermanentlyInvalidatedException) {
            forget(alias, keyDead = true)
        } catch (e: UnrecoverableKeyException) {
            forget(alias, keyDead = true)
        } catch (e: KeyStoreException) {
            forget(alias, keyDead = true)
        } catch (e: GeneralSecurityException) {
            // A bad tag, a wrong IV: this blob does not open with this key. The key itself is fine.
            forget(alias, keyDead = false)
        } catch (e: IllegalArgumentException) {
            forget(alias, keyDead = false)
        }
    }

    override fun delete(alias: String) {
        blobs.remove(alias)
    }

    /**
     * Removes a value that cannot be opened, and the Keystore key when that key is dead.
     *
     * @param alias the name the value is stored under.
     * @param keyDead `true` when the Keystore key can no longer open anything.
     * @return always `null`: the value is gone.
     */
    private fun forget(alias: String, keyDead: Boolean): ByteArray? {
        blobs.remove(alias)
        if (keyDead) cipher.discardKey()
        return null
    }

    private companion object {
        const val PREFS_NAME = "whistl.secrets"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** Where the sealed blobs are kept, as base64 text by alias: private SharedPreferences on the device, a map in tests. */
internal interface BlobStore {
    /** @return the text stored under [alias], or `null`. */
    fun read(alias: String): String?

    /** @param alias the name. @param encoded the base64 text to store. */
    fun write(alias: String, encoded: String)

    /** @param alias the name whose text is removed, if any. */
    fun remove(alias: String)
}

/** Seals and opens blobs under the device's wrapping key. A blob is the 12-byte IV followed by the ciphertext and its tag. */
internal interface WrappingCipher {
    /**
     * @param value the plaintext.
     * @return the IV followed by the ciphertext and tag.
     */
    fun seal(value: ByteArray): ByteArray

    /**
     * @param blob the IV followed by the ciphertext and tag, as [seal] returns it.
     * @return the plaintext.
     * @throws java.security.GeneralSecurityException, or a Keystore error, when the blob does not open.
     */
    fun open(blob: ByteArray): ByteArray

    /** Deletes the wrapping key, so the next [seal] creates a new one. */
    fun discardKey()
}

/** [BlobStore] over private SharedPreferences. */
internal class SharedPreferencesBlobs(private val prefs: SharedPreferences) : BlobStore {
    override fun read(alias: String): String? = prefs.getString(alias, null)

    override fun write(alias: String, encoded: String) {
        prefs.edit().putString(alias, encoded).apply()
    }

    override fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }
}

/** [WrappingCipher] over the Android Keystore: one AES-256-GCM key, created on first use, never exported. */
internal class AndroidWrappingCipher : WrappingCipher {

    override fun seal(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length from Keystore" }
        return iv + cipher.doFinal(value)
    }

    override fun open(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val sealed = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed)
    }

    override fun discardKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    /** The Keystore key that seals every value; created once, never exported. */
    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "io.github.werlang.whistl.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
