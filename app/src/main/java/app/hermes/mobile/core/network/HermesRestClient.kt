package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.model.NativeAuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

open class HermesRestClient(
    val client: OkHttpClient = defaultClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) {
    companion object {
        fun defaultClient(certificateFingerprint: String? = null): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
            return TlsFingerprintTrust.configureClient(builder, certificateFingerprint).build()
        }

        fun forHost(certificateFingerprint: String?): HermesRestClient {
            return HermesRestClient(client = defaultClient(certificateFingerprint))
        }
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    private fun validateUrlScheme(url: String, allowCleartext: Boolean) {
        if (!allowCleartext && url.startsWith("http://", ignoreCase = true)) {
            throw SecurityException("Cleartext HTTP is not allowed unless explicitly permitted in connection settings.")
        }
    }

    open suspend fun getStatus(baseUrl: String, allowCleartext: Boolean = false): Result<HermesServerStatus> =
        withContext(Dispatchers.IO) {
            try {
                val base = normalizeBaseUrl(baseUrl)
                validateUrlScheme(base, allowCleartext)
                val url = "$base/api/status"
                val request = Request.Builder()
                    .url(url)
                    .header("Connection", "close")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string()
                        return@withContext Result.failure(
                            HermesHttpException(response.code, errBody)
                        )
                    }
                    val body = response.body?.string() ?: "{}"
                    val status = json.decodeFromString<HermesServerStatus>(body)
                    Result.success(status)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun exchangeNativeToken(
        baseUrl: String,
        code: String,
        codeVerifier: String,
        allowCleartext: Boolean = false
    ): Result<NativeAuthTokens> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeBaseUrl(baseUrl)
            validateUrlScheme(base, allowCleartext)
            val url = "$base/auth/native/token"

            val payload = buildJsonObject {
                put("code", code)
                put("code_verifier", codeVerifier)
            }
            val requestBody = payload.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    return@withContext Result.failure(
                        HermesHttpException(response.code, errBody)
                    )
                }
                val body = response.body?.string() ?: "{}"
                val rawTokens = json.decodeFromString<NativeAuthTokens>(body)
                val tokens = if (rawTokens.expiresAt == 0L && rawTokens.expiresIn != null && rawTokens.expiresIn > 0) {
                    rawTokens.copy(expiresAt = System.currentTimeMillis() / 1000 + rawTokens.expiresIn)
                } else {
                    rawTokens
                }
                Result.success(tokens)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshNativeToken(
        baseUrl: String,
        refreshToken: String,
        provider: String = "",
        allowCleartext: Boolean = false
    ): Result<NativeAuthTokens> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeBaseUrl(baseUrl)
            validateUrlScheme(base, allowCleartext)
            val url = "$base/auth/native/refresh"

            val payload = buildJsonObject {
                put("refresh_token", refreshToken)
                if (provider.isNotEmpty()) {
                    put("provider", provider)
                }
            }
            val requestBody = payload.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    return@withContext Result.failure(
                        HermesHttpException(response.code, errBody)
                    )
                }
                val body = response.body?.string() ?: "{}"
                val rawTokens = json.decodeFromString<NativeAuthTokens>(body)
                val tokens = if (rawTokens.expiresAt == 0L && rawTokens.expiresIn != null && rawTokens.expiresIn > 0) {
                    rawTokens.copy(expiresAt = System.currentTimeMillis() / 1000 + rawTokens.expiresIn)
                } else {
                    rawTokens
                }
                Result.success(tokens)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mintWsTicket(
        baseUrl: String,
        accessToken: String,
        allowCleartext: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeBaseUrl(baseUrl)
            validateUrlScheme(base, allowCleartext)
            val url = "$base/api/auth/ws-ticket"

            val emptyBody = "{}".toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    return@withContext Result.failure(
                        HermesHttpException(response.code, errBody)
                    )
                }
                val body = response.body?.string() ?: "{}"
                val root = json.decodeFromString<JsonObject>(body)
                val ticket = root["ticket"]?.jsonPrimitive?.content
                    ?: root["ws_ticket"]?.jsonPrimitive?.content
                if (ticket != null) {
                    Result.success(ticket)
                } else {
                    Result.failure(IOException("No ticket returned in response"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
