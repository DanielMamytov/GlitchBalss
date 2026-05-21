package np.sairwv.glitchballs.launch

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import np.sairwv.glitchballs.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

class ConfigRepository(
    private val context: Context,
    private val preferences: LaunchPreferences,
) {

    companion object {
        private const val TAG = "GlitchConfig"
    }

    suspend fun refreshPushTokenCache(): Boolean {
        val latestToken = resolvePushToken()
        if (latestToken.isNotBlank()) {
            preferences.pushToken = latestToken
            return true
        }
        return false
    }

    suspend fun awaitPushTokenCache(timeoutMillis: Long = 15_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (refreshPushTokenCache()) {
                return true
            }
            delay(1_000L)
        }
        return refreshPushTokenCache()
    }

    suspend fun requestConfig(launchData: AppsFlyerLaunchData): ConfigDecision {
        if (BuildConfig.CONFIG_ENDPOINT.isBlank()) {
            Log.e(TAG, "Config endpoint is blank")
            return ConfigDecision.Failure("Missing GLITCH_CONFIG_ENDPOINT")
        }

        Log.d(
            TAG,
            "Requesting config from ${BuildConfig.CONFIG_ENDPOINT} " +
                    "afId=${launchData.afId.ifBlank { "<empty>" }} " +
                    "conversionKeys=${launchData.conversionData.keys} " +
                    "deepLinkKeys=${launchData.deepLinkData.keys}",
        )

        return withContext(Dispatchers.IO) {
            val connection = (URL(BuildConfig.CONFIG_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val payload = buildPayload(launchData)
                Log.d(TAG, "Config request payload=${payload.redactedForLog()}")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                }

                val statusCode = connection.responseCode
                val isHttpSuccess = statusCode in 200..299
                val stream = if (isHttpSuccess) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.d(TAG, "Config response code=$statusCode body=$responseBody")

                if (!isHttpSuccess) {
                    val message = extractErrorMessage(
                        responseBody = responseBody,
                        fallback = "Config HTTP error $statusCode",
                    )
                    Log.w(TAG, "Config rejected by HTTP status: $message")
                    return@withContext ConfigDecision.Rejected(message)
                }

                if (responseBody.isBlank()) {
                    Log.e(TAG, "Config response body is empty")
                    return@withContext ConfigDecision.Rejected("Empty config response")
                }

                val responseJson = try {
                    JSONObject(responseBody)
                } catch (exception: JSONException) {
                    Log.e(TAG, "Config response is not valid JSON", exception)
                    return@withContext ConfigDecision.Rejected("Invalid config response")
                }

                if (!responseJson.optBoolean("ok", false)) {
                    val message = responseJson.optString("message", "Config returned ok=false")
                    Log.w(TAG, "Config rejected message=$message")
                    return@withContext ConfigDecision.Rejected(message)
                }

                val targetUrl = responseJson.optString("url")
                if (targetUrl.isBlank()) {
                    Log.e(TAG, "Config returned ok=true but url is blank")
                    return@withContext ConfigDecision.Rejected("Config url is blank")
                }

                val expiresAt = parseExpiresAt(responseJson.opt("expires"))
                Log.d(TAG, "Config success url=$targetUrl expiresAt=$expiresAt")
                ConfigDecision.Success(targetUrl, expiresAt)
            } catch (exception: Exception) {
                Log.e(TAG, "Config request failed", exception)
                ConfigDecision.Failure(exception.message ?: "Config request failed")
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun buildPayload(launchData: AppsFlyerLaunchData): JSONObject {
        val payload = JSONObject()

        if (launchData.conversionData.isNotEmpty()) {
            payload.putAny("conversion_data", launchData.conversionData)
        }
        if (launchData.deepLinkData.isNotEmpty()) {
            payload.putAny("deep_link_data", launchData.deepLinkData)
        }

        launchData.conversionData.forEach { (key, value) ->
            payload.putAny(key, value)
        }

        launchData.deepLinkData.forEach { (key, value) ->
            if (isMeaningfulPayloadValue(value) || !payload.has(key)) {
                payload.putAny(key, value)
            }
        }

        payload.put("af_id", launchData.afId)
        if (launchData.afId.isNotBlank()) {
            payload.put("appsflyer_id", launchData.afId)
            payload.put("appsflyer_uid", launchData.afId)
            if (!payload.has("sub_id_10")) {
                payload.put("sub_id_10", launchData.afId)
            }
        }
        payload.put("bundle_id", context.packageName)
        payload.put("os", "Android")
        payload.put("store_id", resolveStoreId())
        payload.put("locale", resolveLocale())

        val pushToken = resolvePushToken()
        if (pushToken.isNotBlank()) {
            payload.put("push_token", pushToken)
            payload.put("fcm_token", pushToken)
            payload.put("firebase_token", pushToken)
        }

        val firebaseProjectNumber = resolveFirebaseProjectNumber()
        if (firebaseProjectNumber.isNotBlank()) {
            payload.put("firebase_project_id", firebaseProjectNumber)
            payload.put("firebase_project_number", firebaseProjectNumber)
            payload.put("gcm_sender_id", firebaseProjectNumber)
        }

        return payload
    }

    private fun isMeaningfulPayloadValue(value: Any?): Boolean {
        return when (value) {
            null -> false
            is String -> value.isNotBlank()
            is Collection<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }
    }

    private suspend fun resolvePushToken(): String {
        if (FirebaseApp.getApps(context).isEmpty()) {
            return preferences.pushToken
        }

        val freshToken = withTimeoutOrNull(5_000L) {
            runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        }.orEmpty()

        if (freshToken.isNotBlank()) {
            return freshToken
        }
        return preferences.pushToken
    }

    private fun resolveFirebaseProjectNumber(): String {
        return FirebaseApp.getApps(context).firstOrNull()?.options?.gcmSenderId.orEmpty()
    }

    private fun resolveStoreId(): String {
        return BuildConfig.STORE_ID.ifBlank { context.packageName }
    }

    private fun resolveLocale(): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return locale.toLanguageTag()
    }

    private fun parseExpiresAt(rawValue: Any?): Long {
        return when (rawValue) {
            is Number -> {
                val numeric = rawValue.toLong()
                if (numeric < 1_000_000_000_000L) numeric * 1_000L else numeric
            }

            is String -> {
                rawValue.toLongOrNull()?.let { numeric ->
                    if (numeric < 1_000_000_000_000L) numeric * 1_000L else numeric
                } ?: runCatching { Instant.parse(rawValue).toEpochMilli() }.getOrElse {
                    System.currentTimeMillis()
                }
            }

            else -> System.currentTimeMillis()
        }
    }

    private fun extractErrorMessage(responseBody: String, fallback: String): String {
        if (responseBody.isBlank()) {
            return fallback
        }
        return runCatching {
            JSONObject(responseBody).optString("message", fallback)
        }.getOrDefault(fallback)
    }

    private fun JSONObject.redactedForLog(): JSONObject {
        return JSONObject(toString()).apply {
            listOf("push_token", "fcm_token", "firebase_token").forEach { key ->
                if (has(key)) {
                    put(key, "<present>")
                }
            }
        }
    }
}