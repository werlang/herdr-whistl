package io.github.werlang.whistl.core.secure

/**
 * Secret bytes stored by alias: the client private key and any saved password (SPEC §6).
 *
 * Implementations keep the bytes encrypted at rest. Callers never log or print the values, and never put them in
 * a `toString`. Tests use an in-memory fake; the Android implementation is [KeystoreSecretStore].
 */
interface SecretStore {
    /**
     * Stores [value] under [alias], replacing any earlier value.
     *
     * @param alias the name the value is stored under.
     * @param value the secret bytes.
     */
    fun put(alias: String, value: ByteArray)

    /**
     * Reads the value stored under [alias].
     *
     * @param alias the name the value is stored under.
     * @return the secret bytes, or `null` when nothing is stored under [alias].
     */
    fun get(alias: String): ByteArray?

    /**
     * Removes the value stored under [alias], if any.
     *
     * @param alias the name the value is stored under.
     */
    fun delete(alias: String)
}
