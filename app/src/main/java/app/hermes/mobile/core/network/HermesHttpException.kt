package app.hermes.mobile.core.network

import java.io.IOException

class HermesHttpException(
    val statusCode: Int,
    val errorBody: String? = null
) : IOException("HTTP $statusCode")
