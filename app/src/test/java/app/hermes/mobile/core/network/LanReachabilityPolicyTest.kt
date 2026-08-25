package app.hermes.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.xml.parsers.DocumentBuilderFactory

class LanReachabilityPolicyTest {

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
            "base-config must explicitly set cleartextTrafficPermitted=\"false\" to enforce strict TLS",
            "false",
            cleartextPermitted
        )
    }

    @Test
    fun testDomainConfigDoesNotIncludeArbitraryLanIps() {
        val file = loadConfigFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val domainConfigs = doc.getElementsByTagName("domain-config")

        val domains = mutableListOf<String>()
        for (i in 0 until domainConfigs.length) {
            val dc = domainConfigs.item(i) as Element
            val domainNodes = dc.getElementsByTagName("domain")
            for (j in 0 until domainNodes.length) {
                val d = domainNodes.item(j) as Element
                domains.add(d.textContent.trim())
            }
        }

        // Anti-checklist #1: Hardcoded raw LAN IPs (like 192.168.1.50) must not be in domain-config
        assertFalse("Domain-config must not whitelist single LAN IPv4 addresses like 192.168.1.50", domains.contains("192.168.1.50"))
        assertFalse("Domain-config must not whitelist generic LAN 192.168.x.x IPs", domains.any { it.startsWith("192.168.") })
        assertFalse("Domain-config must not whitelist 10.x.x.x other than 10.0.2.2 / 10.0.3.2 emulators", domains.any { it.startsWith("10.") && it != "10.0.2.2" && it != "10.0.3.2" })
    }

    @Test
    fun testTlsFingerprintTrustWithSelfSignedCertFingerprint() {
        val cert = io.mockk.mockk<X509Certificate>()
        val dummyEncoded = "SelfSignedLanHostCertBytes2026".toByteArray()
        io.mockk.every { cert.encoded } returns dummyEncoded

        val expectedFingerprint = TlsFingerprintTrust.computeSha256Fingerprint(cert)
        assertNotNull(expectedFingerprint)

        val trustManager = TlsFingerprintTrust.createTrustManager(expectedFingerprint)

        // Matching fingerprint must succeed
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")

        // Mismatched fingerprint must fail with CertificateException
        val wrongFingerprint = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
        val strictTrustManager = TlsFingerprintTrust.createTrustManager(wrongFingerprint)
        assertThrows(CertificateException::class.java) {
            strictTrustManager.checkServerTrusted(arrayOf(cert), "RSA")
        }

        // Empty chain must fail
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(emptyArray(), "RSA")
        }
    }
}
