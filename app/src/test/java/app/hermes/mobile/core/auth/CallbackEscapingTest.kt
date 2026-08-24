package app.hermes.mobile.core.auth

import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI

class CallbackEscapingTest {

    @Test
    fun testCallbackErrorIsNotReflectedInHtmlOutput() {
        runBlocking {
            val authManager = PkceLoopbackAuthManager(
                restClient = HermesRestClient(),
                tokenVault = InMemoryTokenVault()
            )

            val authUrlDeferred = CompletableDeferred<String>()
            val authFlowJob = async(Dispatchers.IO) {
                authManager.startAuthFlow(
                    context = null,
                    connectionId = "test-host",
                    baseUrl = "https://127.0.0.1:8443",
                    allowCleartext = true,
                    onAuthUrlReady = { url ->
                        authUrlDeferred.complete(url)
                    }
                )
            }

            val authUrl = withTimeout(5000) {
                authUrlDeferred.await()
            }

            // Extract redirect_uri port from authUrl
            val uri = URI.create(authUrl)
            val queryPairs = uri.query.split("&").associate {
                val idx = it.indexOf('=')
                if (idx > 0) it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8") else it to ""
            }
            val redirectUri = queryPairs["redirect_uri"] ?: ""
            val redirectParsed = URI.create(redirectUri)
            val port = redirectParsed.port

            // Inject malicious XSS payload into the callback error param
            val maliciousPayload = "<script>alert('xss')</script>"
            val clientSocket = Socket("127.0.0.1", port)
            clientSocket.use { s ->
                val out = s.getOutputStream()
                val request = "GET /callback?error=%3Cscript%3Ealert('xss')%3C/script%3E HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
                out.write(request.toByteArray())
                out.flush()

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val responseBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    responseBuilder.append(line).append("\n")
                }
                val responseText = responseBuilder.toString()

                // Verification: Error value must NEVER be reflected into HTML output
                assertFalse(
                    "Malicious payload was reflected into HTML output! Potential XSS vulnerability. Output was:\n$responseText",
                    responseText.contains("<script>") || responseText.contains("alert('xss')") || responseText.contains(maliciousPayload)
                )
            }

            authFlowJob.await()
        }
    }
}
