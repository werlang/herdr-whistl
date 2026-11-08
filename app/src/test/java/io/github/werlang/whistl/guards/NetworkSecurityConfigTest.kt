package io.github.werlang.whistl.guards

import io.github.werlang.whistl.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard (phase 6 P0-12): the app refuses cleartext network traffic. The manifest points at the config, and the config's
 * `base-config` sets `cleartextTrafficPermitted="false"`, so every domain inherits the refusal.
 */
class NetworkSecurityConfigTest {

    private val root: File = Fixtures.repoRoot()

    /** The manifest's application element references the network security config. */
    @Test
    fun theManifestReferencesTheNetworkSecurityConfig() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "the application element names @xml/network_security_config",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }

    /** The base config refuses cleartext for every domain, and there is exactly one base config. */
    @Test
    fun theBaseConfigRefusesCleartextTraffic() {
        val file = File(root, "app/src/main/res/xml/network_security_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        val baseConfigs = document.getElementsByTagName("base-config")
        assertEquals("one base-config", 1, baseConfigs.length)
        assertEquals("false", (baseConfigs.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
    }
}
