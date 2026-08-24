package app.hermes.mobile.core.network

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsFingerprintTrust {

    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    fun normalizeFingerprint(fp: String): String {
        return fp.replace(":", "").replace(" ", "").trim().uppercase()
    }

    fun createTrustManager(expectedFingerprint: String?): X509TrustManager {
        val defaultTrustManager = getDefaultTrustManager()
        if (expectedFingerprint.isNullOrBlank()) {
            return defaultTrustManager
        }

        val normalizedExpected = normalizeFingerprint(expectedFingerprint)

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }

                // Check leaf certificate fingerprint
                val leafCert = chain[0]
                val leafFp = normalizeFingerprint(computeSha256Fingerprint(leafCert))

                if (leafFp == normalizedExpected) {
                    return
                }

                val anyMatch = chain.any { cert ->
                    normalizeFingerprint(computeSha256Fingerprint(cert)) == normalizedExpected
                }

                if (anyMatch) {
                    return
                }

                throw CertificateException(
                    "Certificate fingerprint mismatch! Expected: $expectedFingerprint, Actual: ${computeSha256Fingerprint(leafCert)}"
                )
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrustManager.acceptedIssuers
            }
        }
    }

    fun createSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return sslContext.socketFactory
    }

    private fun getDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trustManagers = tmf.trustManagers
        return trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun configureClient(builder: OkHttpClient.Builder, certificateFingerprint: String?): OkHttpClient.Builder {
        if (!certificateFingerprint.isNullOrBlank()) {
            val trustManager = createTrustManager(certificateFingerprint)
            val sslSocketFactory = createSocketFactory(trustManager)
            builder.sslSocketFactory(sslSocketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder
    }
}
