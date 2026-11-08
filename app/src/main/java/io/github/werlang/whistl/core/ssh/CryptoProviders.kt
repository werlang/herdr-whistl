package io.github.werlang.whistl.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/** The JCA name BouncyCastle is registered under. sshj looks its providers up by this name. */
const val BOUNCY_CASTLE_PROVIDER_NAME: String = "BC"

/**
 * The JCA and sshj operations [CryptoProviders] needs. Production uses [JavaSecurityRegistry]; tests use a fake that
 * records the calls.
 */
interface SecurityRegistry {
    /**
     * Removes the provider registered under [name], if one is.
     *
     * @param name the provider name.
     */
    fun remove(name: String)

    /**
     * Inserts [provider] at a 1-based [position], as `java.security.Security.insertProviderAt` does.
     *
     * @param provider the provider to insert.
     * @param position the 1-based position.
     */
    fun insert(provider: Provider, position: Int)

    /**
     * Tells sshj to use the provider registered under [name] for its crypto, and to register BouncyCastle by itself only
     * when no provider of that name exists.
     *
     * @param name the provider name.
     */
    fun useForSshj(name: String)
}

/** [SecurityRegistry] over the real `java.security.Security` and sshj's `SecurityUtils`. */
object JavaSecurityRegistry : SecurityRegistry {
    override fun remove(name: String) {
        Security.removeProvider(name)
    }

    override fun insert(provider: Provider, position: Int) {
        Security.insertProviderAt(provider, position)
    }

    override fun useForSshj(name: String) {
        SecurityUtils.setSecurityProvider(name)
        SecurityUtils.setRegisterBouncyCastle(true)
    }
}

/**
 * Makes the bundled BouncyCastle sshj's crypto provider, on every API level (SPEC §6, phase 7 r1c).
 *
 * The platform registers its own provider named `BC`, a stripped copy with no X25519. sshj's key exchange
 * (`curve25519-sha256`) asks `BC` for X25519, so on API 33 and up the platform's copy made the key exchange fail before
 * the host key was checked. Earlier code skipped BouncyCastle on API 33 and up, which is why nothing replaced it.
 *
 * [install] therefore removes whatever `BC` is registered, inserts the bundled provider at position 1, and points sshj at
 * `BC`. The API level is not consulted. It reads no clock and no network.
 *
 * Call it once, before any SSH use (see `AppContainer`).
 */
object CryptoProviders {
    /**
     * Replaces the platform's `BC` with the bundled BouncyCastle, and points sshj at it.
     *
     * @param security the JCA and sshj seam.
     */
    fun install(security: SecurityRegistry) {
        security.remove(BOUNCY_CASTLE_PROVIDER_NAME)
        security.insert(BouncyCastleProvider(), 1)
        security.useForSshj(BOUNCY_CASTLE_PROVIDER_NAME)
    }
}
