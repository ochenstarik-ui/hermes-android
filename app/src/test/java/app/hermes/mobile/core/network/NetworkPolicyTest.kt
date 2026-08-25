package app.hermes.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkPolicyTest {

    private fun loadConfigFile(): File {
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
            File("../app/src/main/res/xml/network_security_config.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("network_security_config.xml not found in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun testBaseConfigDisallowsCleartextTraffic() {
        val file = loadConfigFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val baseConfig = doc.getElementsByTagName("base-config").item(0) as? Element
        assertNotNull("base-config element must exist in network_security_config.xml", baseConfig)

        val cleartextPermitted = baseConfig?.getAttribute("cleartextTrafficPermitted")
        assertEquals(
            "base-config must explicitly set cleartextTrafficPermitted=\"false\"",
            "false",
            cleartextPermitted
        )
    }

    @Test
    fun testBaseConfigDoesNotTrustUserCertificates() {
        val file = loadConfigFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val baseConfig = doc.getElementsByTagName("base-config").item(0) as? Element
        assertNotNull("base-config element must exist", baseConfig)

        val certElements = baseConfig!!.getElementsByTagName("certificates")
        for (i in 0 until certElements.length) {
            val cert = certElements.item(i) as Element
            val src = cert.getAttribute("src")
            assertFalse(
                "base-config must NOT contain user certificates src=\"user\". Found: $src",
                src.equals("user", ignoreCase = true)
            )
        }
    }

    @Test
    fun testDebugOverridesConfiguredForUserCertificates() {
        val file = loadConfigFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val debugOverrides = doc.getElementsByTagName("debug-overrides").item(0) as? Element
        assertNotNull("debug-overrides must exist in network_security_config.xml", debugOverrides)

        val certElements = debugOverrides!!.getElementsByTagName("certificates")
        var hasUserCert = false
        for (i in 0 until certElements.length) {
            val cert = certElements.item(i) as Element
            if (cert.getAttribute("src") == "user") {
                hasUserCert = true
            }
        }
        assertTrue("debug-overrides must include certificates src=\"user\"", hasUserCert)
    }

    @Test
    fun testDomainConfigRestrictedToLocalDomains() {
        val file = loadConfigFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val domainConfigs = doc.getElementsByTagName("domain-config")
        assertTrue("Must have at least one domain-config for cleartext local traffic", domainConfigs.length > 0)

        var permitsCleartext = false
        val domains = mutableListOf<String>()
        for (i in 0 until domainConfigs.length) {
            val dc = domainConfigs.item(i) as Element
            if (dc.getAttribute("cleartextTrafficPermitted") == "true") {
                permitsCleartext = true
            }
            val domainNodes = dc.getElementsByTagName("domain")
            for (j in 0 until domainNodes.length) {
                val d = domainNodes.item(j) as Element
                domains.add(d.textContent.trim())
            }
        }

        assertTrue("domain-config must set cleartextTrafficPermitted=\"true\" for local hosts", permitsCleartext)
        assertTrue(
            "domain-config must include localhost, 127.0.0.1, or 10.0.2.2. Found: $domains",
            domains.contains("localhost") || domains.contains("127.0.0.1") || domains.contains("10.0.2.2")
        )
    }
}
