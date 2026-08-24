package app.hermes.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class TlsFingerprintTrustTest {

    // Test X.509 certificate in Base64 DER format
    private val testCertPem = """
        -----BEGIN CERTIFICATE-----
        MIIBkTCB+wIJAKHH0eJqO6/ZMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBlRl
        c3RDQTAeFw0yNDA4MjQwMDAwMDBaFw0zNDA4MjQwMDAwMDBaMBExDzANBgNVBAMM
        BlRlc3RDQTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQC1y3U05q0i3vUuBfvZ7J8Y
        +8r9rB9yD0X/zU+7q6u9mO4w9a4uR7N3u1o3h0d0e6wB8/m+1a2c3d4e5f6g7h8i
        AgMBAAEwDQYJKoZIhvcNAQELBQADQQAvk4qB2F+zY7R0Q9Z1q7c3a0s2p3u5v6w8
        x9y0z1a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z7a8b9c=
        -----END CERTIFICATE-----
    """.trimIndent()

    private fun generateTestCertificate(): X509Certificate {
        // Self-signed X509 certificate using standard Java cert or simulated certificate
        val certPem = """
            -----BEGIN CERTIFICATE-----
            MIICpDCCAYwCCQDU+pQ2YOnmPzANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls
            b2NhbGhvc3QwHhcNMjQwODIwMDAwMDAwWhcNMzQwODE4MDAwMDAwWjAUMRIwEAYD
            VQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDA
            O1t3VwOcvG0Zqg1X+b0F6k1O6+R9e2r8j4N+w0G0r6q4t4h3O2a8h1z8y9e0s1a2
            b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z7a8b9c0d1e2f3g4
            h5i6j7k8l9m0n1o2p3q4r5s6t7u8v9w0x1y2z3a4b5c6d7e8f9g0h1i2j3k4l5m6
            n7o8p9q0r1s2t3u4v5w6x7y8z9a0b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8
            t9u0AgMBAAEwDQYJKoZIhvcNAQELBQADggEBALc4qB2F+zY7R0Q9Z1q7c3a0s2p3
            u5v6w8x9y0z1a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z7
            a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2p3q4r5s6t7u8v9w0x1y2z3a4b5c6d7e8f9
            g0h1i2j3k4l5m6n7o8p9q0r1s2t3u4v5w6x7y8z9a0b1c2d3e4f5g6h7i8j9k0l1
            m2n3o4p5q6r7s8t9u0v1w2x3y4z5a6b7c8d9e0f1g2h3i4j5k6l7m8n9o0p1q2r3
            s4t5u6v7w8x9y0z=
            -----END CERTIFICATE-----
        """.trimIndent()
        // If parsing raw string fails, create a mock / generated X509Certificate
        val cf = CertificateFactory.getInstance("X.509")
        return try {
            cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
        } catch (_: Exception) {
            // Fallback to minimal DER
            mockCertificate()
        }
    }

    private fun mockCertificate(): X509Certificate {
        val cert = io.mockk.mockk<X509Certificate>()
        val dummyEncoded = "DummyCertBytesForFingerprintTesting".toByteArray()
        io.mockk.every { cert.encoded } returns dummyEncoded
        return cert
    }

    @Test
    fun testFingerprintCalculationAndNormalization() {
        val cert = mockCertificate()
        val fp = TlsFingerprintTrust.computeSha256Fingerprint(cert)
        assertNotNull(fp)
        val normalized = TlsFingerprintTrust.normalizeFingerprint(fp)
        assertEquals(fp.replace(":", "").uppercase(), normalized)
    }

    @Test
    fun testMatchingFingerprintPassesVerification() {
        val cert = mockCertificate()
        val expectedFp = TlsFingerprintTrust.computeSha256Fingerprint(cert)
        val trustManager = TlsFingerprintTrust.createTrustManager(expectedFp)

        // Should not throw CertificateException
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun testMismatchedFingerprintThrowsCertificateException() {
        val cert = mockCertificate()
        val wrongFp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val trustManager = TlsFingerprintTrust.createTrustManager(wrongFp)

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
}
