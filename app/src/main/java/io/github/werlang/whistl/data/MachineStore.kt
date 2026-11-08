package io.github.werlang.whistl.data

import io.github.werlang.whistl.domain.Machine

/**
 * How a machine logs in (SPEC §6): with the app's client key, or with a saved password. The password itself is never
 * in the machine record; it lives in `SecretStore` under [MachinesRepository]'s password alias.
 */
enum class AuthMethod {
    /** The app's Ed25519 client key (see `KeyManager`). */
    Key,

    /** A password saved through `SecretStore`. */
    Password,
}

/**
 * A machine as stored: its settings and how it logs in. Holds no secrets.
 *
 * @property machine the machine's settings.
 * @property auth how it logs in.
 */
data class StoredMachine(
    val machine: Machine,
    val auth: AuthMethod,
)

/**
 * Persists the machine list. The repository is the only caller. Implementations are an in-memory fake for tests now,
 * and a persistent store in a later round.
 */
interface MachineStore {
    /**
     * Every stored machine, in the order the user added them.
     *
     * @return the stored machines.
     */
    fun list(): List<StoredMachine>

    /**
     * Stores or replaces a machine by its id.
     *
     * @param stored the machine to store.
     */
    fun put(stored: StoredMachine)

    /**
     * Removes a machine by id, if present.
     *
     * @param id the machine id.
     */
    fun delete(id: String)
}
