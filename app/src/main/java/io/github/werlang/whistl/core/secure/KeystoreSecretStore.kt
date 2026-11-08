package io.github.werlang.whistl.core.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
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
 * Thin by design: it uses `android.*` APIs, so JVM tests do not reference it. Their fake is in `app/src/test`.
 *
 * @param context any context; the application context is kept.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun put(alias: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length from Keystore" }
        val sealed = cipher.doFinal(value)
        prefs.edit().putString(alias, Base64.getEncoder().encodeToString(iv + sealed)).apply()
    }

    override fun get(alias: String): ByteArray? {
        val encoded = prefs.getString(alias, null) ?: return null
        val blob = Base64.getDecoder().decode(encoded)
        val iv = blob.copyOfRange(0, IV_BYTES)
        val sealed = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed)
    }

    override fun delete(alias: String) {
        prefs.edit().remove(alias).apply()
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
        const val PREFS_NAME = "whistl.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
