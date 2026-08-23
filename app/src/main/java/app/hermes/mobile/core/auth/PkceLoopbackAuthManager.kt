package app.hermes.mobile.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.hermes.mobile.core.model.NativeAuthTokens
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class PkceLoopbackAuthManager(
    private val restClient: HermesRestClient,
    private val tokenVault: TokenVault
) {
    suspend fun startAuthFlow(
        context: Context?,
        connectionId: String,
        baseUrl: String,
        provider: String = "github",
        allowCleartext: Boolean = false,
        onAuthUrlReady: ((String) -> Unit)? = null
    ): Result<NativeAuthTokens> = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val port = serverSocket.localPort
            serverSocket.soTimeout = 180_000 // 3 minutes timeout

            val state = UUID.randomUUID().toString()
            val challenge = PkceChallenge.generate()
            val redirectUri = "http://127.0.0.1:$port/callback"

            val encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
            val encodedChallenge = URLEncoder.encode(challenge.codeChallenge, StandardCharsets.UTF_8.name())
            val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8.name())
            val encodedProvider = URLEncoder.encode(provider, StandardCharsets.UTF_8.name())

            val cleanBase = baseUrl.trimEnd('/')
            val authUrl = "$cleanBase/auth/native/authorize?" +
                    "provider=$encodedProvider" +
                    "&code_challenge=$encodedChallenge" +
                    "&code_challenge_method=S256" +
                    "&redirect_uri=$encodedRedirect" +
                    "&state=$encodedState"

            if (onAuthUrlReady != null) {
                onAuthUrlReady(authUrl)
            } else if (context != null) {
                openBrowser(context, authUrl)
            }

            val socket: Socket = serverSocket.accept()
            val authCode = handleCallbackSocket(socket, state)

            val exchangeResult = restClient.exchangeNativeToken(
                baseUrl = cleanBase,
                code = authCode,
                codeVerifier = challenge.codeVerifier,
                allowCleartext = allowCleartext
            )

            if (exchangeResult.isSuccess) {
                val tokens = exchangeResult.getOrThrow()
                tokenVault.saveTokens(connectionId, tokens)
                Result.success(tokens)
            } else {
                Result.failure(exchangeResult.exceptionOrNull() ?: Exception("Token exchange failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleCallbackSocket(socket: Socket, expectedState: String): String {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val firstLine = reader.readLine() ?: throw IllegalStateException("Empty HTTP request received")

            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                throw IllegalStateException("Invalid HTTP request method: $firstLine")
            }

            val pathAndQuery = parts[1]
            val queryIndex = pathAndQuery.indexOf('?')
            if (queryIndex == -1) {
                sendHtmlResponse(s, 400, "Missing authorization parameters")
                throw IllegalStateException("Missing query parameters in callback URL: $pathAndQuery")
            }

            val query = pathAndQuery.substring(queryIndex + 1)
            val queryParams = parseQueryParams(query)

            val returnedState = queryParams["state"]
            val authCode = queryParams["code"]
            val error = queryParams["error"]

            if (error != null) {
                sendHtmlResponse(s, 400, "Authorization Error: $error")
                throw IllegalStateException("Server returned authorization error: $error")
            }

            if (returnedState != expectedState) {
                sendHtmlResponse(s, 400, "State mismatch error")
                throw SecurityException("PKCE State mismatch! Possible CSRF attempt.")
            }

            if (authCode.isNullOrEmpty()) {
                sendHtmlResponse(s, 400, "Missing authorization code")
                throw IllegalStateException("Authorization code missing in response")
            }

            sendHtmlResponse(s, 200, "Authentication Successful! You can return to Hermes.")
            return authCode
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                map[key] = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
        }
        return map
    }

    private fun sendHtmlResponse(socket: Socket, statusCode: Int, message: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Hermes Authentication</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 40px 20px; background: #0f172a; color: #f8fafc; }
                    .card { background: #1e293b; max-width: 420px; margin: 0 auto; padding: 32px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
                    h2 { margin-top: 0; color: #38bdf8; }
                    p { color: #94a3b8; font-size: 16px; line-height: 1.5; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>Hermes Authentication</h2>
                    <p>$message</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val statusText = if (statusCode == 200) "OK" else "Bad Request"
        val response = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                html

        val output = socket.getOutputStream()
        output.write(response.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun openBrowser(context: Context, url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
        }
    }
}
