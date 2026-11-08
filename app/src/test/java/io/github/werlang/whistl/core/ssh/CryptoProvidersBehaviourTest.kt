package io.github.werlang.whistl.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.Security
import java.security.Signature

/**
 * The behaviour the fix is for (phase 7 r1c). On the device the platform registers a stripped provider named `BC` with no
 * X25519, and sshj's key exchange asks `BC` for X25519 before any host key is read. This test puts that decoy in front, runs
 * the production [CryptoProviders.install] on the real JCA, and then asks for the same things sshj asks for.
 *
 * It has no API-level case: [CryptoProviders.install] no longer looks at the API level, so the same decoy on API 33 and on
 * API 35 takes the same path. Reverting the fix to the old API-33 early return makes the X25519 lookup fail with
 * `NoSuchAlgorithmException: no such algorithm: X25519 for provider BC`.
 *
 * The JVM's `Security` list is global, so the test leaves a full BouncyCastle registered, as production does.
 */
class CryptoProvidersBehaviourTest {

    /** Stand-in for the platform's `BC` on the device: the same name, and no X25519 or Ed25519. */
    private class DecoyBc : Provider("BC", 1.0, "decoy: platform BC without X25519")

    @Test
    fun sshjGetsAnX25519PairAndAnEd25519SignatureFromTheInstalledProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(DecoyBc(), 1)
        try {
            CryptoProviders.install(JavaSecurityRegistry)

            assertEquals("BC", SecurityUtils.getSecurityProvider())

            // The lookup sshj's curve25519 key exchange makes (DHBase.<init> -> SecurityUtils.getKeyPairGenerator).
            assertNotNull(SecurityUtils.getKeyPairGenerator("X25519").generateKeyPair())

            // The Ed25519 signing sshj uses for host-key and public-key checks, through the same provider name.
            val pair = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
            val message = "whistl ed25519 round trip".toByteArray(Charsets.UTF_8)
            val signed = Signature.getInstance("Ed25519", "BC").run {
                initSign(pair.private)
                update(message)
                sign()
            }
            val verified = Signature.getInstance("Ed25519", "BC").run {
                initVerify(pair.public)
                update(message)
                verify(signed)
            }
            assertTrue(verified)
        } finally {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
