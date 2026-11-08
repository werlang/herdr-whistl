package io.github.werlang.whistl.core.ssh

import android.os.Build
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature

/**
 * The real [CryptoProviders.install] on the API 26 path (phase 4 r1, decision 2), under Robolectric with the device SDK
 * set to 26. It uses the JVM's real `java.security.Security`, not a fake, so it proves that the provider sshj uses is the
 * full BouncyCastle and that Ed25519 works through it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CryptoProvidersRobolectricTest {

    /** At API 26 the `BC` name resolves to the full BouncyCastle, which signs and verifies Ed25519. */
    @Test
    fun api26RegistersFullBouncyCastleAndEd25519RoundTrips() {
        CryptoProviders.install(Build.VERSION.SDK_INT, JavaSecurityRegistry)

        assertEquals(BouncyCastleProvider::class.java, Security.getProvider("BC").javaClass)
        assertEquals("BC", SecurityUtils.getSecurityProvider())

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
    }
}
