package io.github.werlang.whistl.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/** The JCA name BouncyCastle is registered under. sshj looks its providers up by this name. */
const val BOUNCY_CASTLE_PROVIDER_NAME: String = "BC"

/**
 * The first API level whose platform JCA has Ed25519 (SPEC §6). Below it the app brings BouncyCastle as sshj's provider.
 */
const val PLATFORM_ED25519_SDK: Int = 33

/**
 * The JCA and sshj operations [CryptoProviders] needs. Production uses [JavaSecurityRegistry]; tests use a fake that
 * records the calls.
 */
interface SecurityRegistry {
    /**
     * The provider registered under [name].
     *
     * @param name the provider name, for example `BC`.
     * @return the registered provider, or `null` when none is registered.
     */
    fun provider(name: String): Provider?

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
    override fun provider(name: String): Provider? = Security.getProvider(name)

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
 * Makes BouncyCastle sshj's crypto provider on API 26 to 32 (SPEC §6, phase 4 decision 2). From API 33 the platform
 * already has Ed25519 and nothing changes.
 *
 * Below 33 the platform has a stripped provider named `BC`, and it has no Ed25519. [install] removes it and inserts the
 * full BouncyCastle provider at position 1, then points sshj at `BC`. A `BC` provider that already offers Ed25519 is
 * kept, so a second call leaves exactly one `BC` provider. sshj registers BouncyCastle itself only when no `BC` exists,
 * so the provider installed here is the one it uses.
 *
 * Call it once, before any SSH use (see `AppContainer`). It reads no clock and no network.
 */
object CryptoProviders {
    /**
     * Installs BouncyCastle as sshj's provider when the platform lacks Ed25519.
     *
     * @param sdkInt the device's `Build.VERSION.SDK_INT`.
     * @param security the JCA and sshj seam.
     */
    fun install(sdkInt: Int, security: SecurityRegistry) {
        if (sdkInt >= PLATFORM_ED25519_SDK) return
        if (security.provider(BOUNCY_CASTLE_PROVIDER_NAME)?.hasEd25519() != true) {
            security.remove(BOUNCY_CASTLE_PROVIDER_NAME)
            security.insert(BouncyCastleProvider(), 1)
        }
        security.useForSshj(BOUNCY_CASTLE_PROVIDER_NAME)
    }

    /** Whether this provider offers Ed25519 signatures, the algorithm the platform lacks below API 33. */
    private fun Provider.hasEd25519(): Boolean = getService("Signature", "Ed25519") != null
}
