package app.hermes.mobile.feature.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostUrlNormalizationTest {

    @Test
    fun testDefaultMissingSchemeToHttps() {
        assertEquals("https://192.168.1.100:9119", normalizeHostUrl("192.168.1.100:9119"))
        assertEquals("https://my-server.lan:9119", normalizeHostUrl("my-server.lan:9119"))
        assertEquals("https://localhost:8080", normalizeHostUrl("localhost:8080"))
    }

    @Test
    fun testPreservesExplicitHttpAndHttps() {
        assertEquals("http://192.168.1.100:9119", normalizeHostUrl("http://192.168.1.100:9119"))
        assertEquals("https://192.168.1.100:9119", normalizeHostUrl("https://192.168.1.100:9119"))
        assertEquals("HTTP://192.168.1.100:9119", normalizeHostUrl("HTTP://192.168.1.100:9119"))
    }

    @Test
    fun testTrimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://192.168.1.100:9119", normalizeHostUrl("   192.168.1.100:9119/   "))
        assertEquals("https://example.com", normalizeHostUrl("  https://example.com/  "))
        assertEquals("http://example.com:8080", normalizeHostUrl("http://example.com:8080///"))
    }

    @Test
    fun testIpv6Normalization() {
        assertEquals("https://[2001:db8::1]:9119", normalizeHostUrl("[2001:db8::1]:9119"))
        assertEquals("http://[::1]:9119", normalizeHostUrl("http://[::1]:9119"))
    }

    @Test
    fun testMalformedInputsThrowException() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeHostUrl("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeHostUrl("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeHostUrl("ftp://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeHostUrl("https://:8080")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeHostUrl("https://example.com:99999")
        }
    }
}
