package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.secure.SecretStore
import io.github.werlang.whistl.core.ssh.HostKeyStore
import io.github.werlang.whistl.core.ssh.PinnedHostKey

/** In-memory [SecretStore] for tests. Values are kept as given: no encryption, nothing written to disk. */
class FakeSecretStore : SecretStore {
    private val values = mutableMapOf<String, ByteArray>()

    override fun put(alias: String, value: ByteArray) {
        values[alias] = value.copyOf()
    }

    override fun get(alias: String): ByteArray? = values[alias]?.copyOf()

    override fun delete(alias: String) {
        values.remove(alias)
    }
}

/** In-memory [HostKeyStore] for tests. Pins live only as long as the object. */
class FakeHostKeyStore : HostKeyStore {
    private val pins = mutableMapOf<String, PinnedHostKey>()

    override fun pinned(machineId: String): PinnedHostKey? = pins[machineId]

    override fun pin(machineId: String, key: PinnedHostKey) {
        pins[machineId] = key
    }
}
