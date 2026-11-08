package io.github.werlang.whistl.core.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Provider

/**
 * [CryptoProviders.install] on a fake [SecurityRegistry] (phase 4 r1, decision 2): API 26 and 32 register the full
 * BouncyCastle under `BC` at position 1; 33 and up change nothing; a second call leaves one `BC`.
 */
class CryptoProvidersTest {

    /** The platform's stripped `BC`: the same name, and no Ed25519. */
    private class StrippedBc : Provider("BC", 1.0, "stripped platform provider")

    /** Records each call, and holds the providers in `java.security.Security` order (1-based positions). */
    private class FakeSecurityRegistry(vararg initial: Provider) : SecurityRegistry {
        val providers: MutableList<Provider> = initial.toMutableList()
        val calls: MutableList<String> = mutableListOf()
        var sshjProvider: String? = null
            private set

        override fun provider(name: String): Provider? = providers.firstOrNull { it.name == name }

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

    /** API 26: the stripped `BC` is replaced by the full provider at position 1, and sshj is told to use `BC`. */
    @Test
    fun api26ReplacesTheStrippedBcWithTheFullProviderAtPositionOne() {
        val registry = FakeSecurityRegistry(StrippedBc())

        CryptoProviders.install(26, registry)

        assertTrue(registry.providers.first() is BouncyCastleProvider)
        assertEquals(1, registry.providers.count { it.name == "BC" })
        assertEquals("BC", registry.sshjProvider)
        assertEquals(listOf("remove:BC", "insert:BC@1", "sshj:BC"), registry.calls)
    }

    /** API 32 is the last level that gets BouncyCastle. */
    @Test
    fun api32GetsTheSameRegistration() {
        val registry = FakeSecurityRegistry(StrippedBc())

        CryptoProviders.install(32, registry)

        assertTrue(registry.providers.first() is BouncyCastleProvider)
        assertEquals("BC", registry.sshjProvider)
    }

    /** API 33 has Ed25519 in the platform: nothing is removed, inserted or handed to sshj. */
    @Test
    fun api33ChangesNothing() {
        val registry = FakeSecurityRegistry(StrippedBc())
        val before = registry.providers.toList()

        CryptoProviders.install(33, registry)

        assertEquals(before, registry.providers)
        assertTrue(registry.calls.isEmpty())
        assertNull(registry.sshjProvider)
    }

    /** API 35 too: the platform's own provider is left as it is. */
    @Test
    fun api35ChangesNothing() {
        val registry = FakeSecurityRegistry(StrippedBc())

        CryptoProviders.install(35, registry)

        assertTrue(registry.providers.single() is StrippedBc)
        assertTrue(registry.calls.isEmpty())
        assertNull(registry.sshjProvider)
    }

    /** Calling twice leaves one `BC`, and the second call keeps the provider the first call installed. */
    @Test
    fun callingTwiceLeavesOneBouncyCastleProvider() {
        val registry = FakeSecurityRegistry(StrippedBc())
        CryptoProviders.install(26, registry)
        val installed = registry.provider("BC")

        CryptoProviders.install(26, registry)

        assertEquals(1, registry.providers.count { it.name == "BC" })
        assertSame(installed, registry.provider("BC"))
        assertEquals(listOf("remove:BC", "insert:BC@1", "sshj:BC", "sshj:BC"), registry.calls)
    }

    /** A `BC` that already offers Ed25519 is the full provider, so it is kept as it is. */
    @Test
    fun aFullBouncyCastleAlreadyRegisteredIsKept() {
        val full = BouncyCastleProvider()
        val registry = FakeSecurityRegistry(full)

        CryptoProviders.install(26, registry)

        assertSame(full, registry.provider("BC"))
        assertEquals(listOf("sshj:BC"), registry.calls)
    }

    /** The check that tells the stripped platform provider from the full one: Ed25519 is offered by one, not the other. */
    @Test
    fun onlyTheFullProviderOffersEd25519() {
        assertNotNull(BouncyCastleProvider().getService("Signature", "Ed25519"))
        assertNull(StrippedBc().getService("Signature", "Ed25519"))
    }
}
