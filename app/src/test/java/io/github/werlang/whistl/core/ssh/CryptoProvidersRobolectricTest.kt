package io.github.werlang.whistl.core.ssh

import android.os.Build
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.Signature

/**
 * The real [CryptoProviders.install] on the API 26 path (phase 4 decision 2), under Robolectric with the device SDK set to
 * 26. It uses the JVM's real `java.security.Security`, so it proves the position of the provider, not only that some
 * `BC` exists.
 *
 * The stand-in `BC` below plays Android's stripped provider: same name, no Ed25519. Without `install`, the stand-in is still
 * first and this test fails. sshj registers BouncyCastle on its own when it is asked for its provider, so the sshj state is
 * read only after the position assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CryptoProvidersRobolectricTest {

    /** A `BC` with no Ed25519, as the platform's stripped provider has. */
    private class StrippedStandIn : Provider("BC", 1.0, "stand-in for the platform's stripped provider")

    /** The device is API 26, and the platform's `BC` is replaced by the full provider at position 1. */
    @Test
    fun api26InstallPutsTheFullBouncyCastleFirstAndEd25519SignsThroughIt() {
        assertEquals(26, Build.VERSION.SDK_INT)
        Security.removeProvider("BC")
        Security.insertProviderAt(StrippedStandIn(), 1)
        assertNull(Security.getProvider("BC").getService("Signature", "Ed25519"))
        assertThrows(NoSuchAlgorithmException::class.java) { Signature.getInstance("Ed25519", "BC") }

        CryptoProviders.install(Build.VERSION.SDK_INT, JavaSecurityRegistry)

        val first = Security.getProviders()[0]
        assertTrue("first provider is ${first.javaClass.name}", first is BouncyCastleProvider)
        assertEquals(1, Security.getProviders().count { it.name == "BC" })
        assertTrue("the stand-in is still registered: ${Security.getProviders().map { it.name }}", Security.getProviders().none { it is StrippedStandIn })

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
        assertNotNull(Security.getProvider("BC").getService("Signature", "Ed25519"))

        assertEquals("BC", SecurityUtils.getSecurityProvider())
    }
}
