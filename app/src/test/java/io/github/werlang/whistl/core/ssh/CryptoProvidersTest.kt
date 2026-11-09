package io.github.werlang.whistl.core.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Provider

/**
 * [CryptoProviders.install] on a fake [SecurityRegistry] (phase 7 r1c): the bundled BouncyCastle replaces whatever `BC` is
 * registered, goes to position 1, and sshj is pointed at `BC`. The behaviour that matters (sshj really gets X25519 and
 * Ed25519 from it) is in [CryptoProvidersBehaviourTest].
 */
class CryptoProvidersTest {

    /** The platform's stripped `BC`: the same name, and no algorithms at all. */
    private class StrippedBc : Provider("BC", 1.0, "stripped platform provider")

    /** Records each call, and holds the providers in `java.security.Security` order (1-based positions). */
    private class FakeSecurityRegistry(vararg initial: Provider) : SecurityRegistry {
        val providers: MutableList<Provider> = initial.toMutableList()
        val calls: MutableList<String> = mutableListOf()
        var sshjProvider: String? = null
            private set

        override fun remove(name: String) {
            calls += "remove:$name"
            providers.removeAll { it.name == name }
        }

        override fun insert(provider: Provider, position: Int) {
            calls += "insert:${provider.name}@$position"
            providers.add(position - 1, provider)
        }

        override fun useForSshj(name: String) {
            calls += "sshj:$name"
            sshjProvider = name
        }
    }

    /** The platform's `BC` is replaced by the bundled provider at position 1, and sshj is told to use `BC`. */
    @Test
    fun thePlatformBcIsReplacedByTheBundledProviderAtPositionOne() {
        val registry = FakeSecurityRegistry(StrippedBc())

        CryptoProviders.install(registry)

        assertTrue(registry.providers.first() is BouncyCastleProvider)
        assertEquals(1, registry.providers.count { it.name == "BC" })
        assertEquals("BC", registry.sshjProvider)
        assertEquals(listOf("remove:BC", "insert:BC@1", "sshj:BC"), registry.calls)
    }

    /** A `BC` that is already the bundled provider is still replaced: the install does not depend on what is there. */
    @Test
    fun anAlreadyInstalledBundledProviderIsReplacedNotDuplicated() {
        val registry = FakeSecurityRegistry(BouncyCastleProvider())

        CryptoProviders.install(registry)

        assertEquals(1, registry.providers.count { it.name == "BC" })
        assertTrue(registry.providers.first() is BouncyCastleProvider)
        assertEquals(listOf("remove:BC", "insert:BC@1", "sshj:BC"), registry.calls)
    }

    /** Calling twice leaves exactly one `BC`, at position 1, and sshj still uses it. */
    @Test
    fun callingTwiceLeavesOneBouncyCastleProvider() {
        val registry = FakeSecurityRegistry(StrippedBc())

        CryptoProviders.install(registry)
        CryptoProviders.install(registry)

        assertEquals(1, registry.providers.count { it.name == "BC" })
        assertTrue(registry.providers.first() is BouncyCastleProvider)
        assertEquals("BC", registry.sshjProvider)
    }

    /**
     * The algorithms sshj asks `BC` for: the bundled provider has X25519 and Ed25519, and the stripped platform copy has
     * neither. This is the difference the fix makes.
     */
    @Test
    fun onlyTheBundledProviderOffersX25519AndEd25519() {
        val bundled = BouncyCastleProvider()
        assertNotNull(bundled.getService("KeyPairGenerator", "X25519"))
        assertNotNull(bundled.getService("KeyAgreement", "X25519"))
        assertNotNull(bundled.getService("Signature", "Ed25519"))
        assertNull(StrippedBc().getService("KeyPairGenerator", "X25519"))
        assertNull(StrippedBc().getService("Signature", "Ed25519"))
    }
}
