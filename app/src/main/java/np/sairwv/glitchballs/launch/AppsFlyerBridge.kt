package np.sairwv.glitchballs.launch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import np.sairwv.glitchballs.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class AppsFlyerBridge(private val application: Application) {

    companion object {
        private const val TAG = "GlitchBallsAppsFlyer"
        private const val INITIAL_CONVERSION_TIMEOUT_MILLIS = 8_000L
        private const val LATE_CONVERSION_TIMEOUT_MILLIS = 7_000L
        private const val ORGANIC_RETRY_DELAY_MILLIS = 5_000L
        private const val DEEP_LINK_TIMEOUT_MILLIS = 3_000L
        private const val INSTALL_REFERRER_TIMEOUT_MILLIS = 3_000L

        private val DIRECT_DEEP_LINK_KEYS = setOf(
            "af_android_url",
            "af_dp",
            "af_force_deeplink",
            "af_ios_url",
            "af_web_dp",
            "clickid",
            "deep_link",
            "deep_link_value",
            "is_deferred",
            "is_retargeting",
            "link",
            "url",
        )
    }

    private val conversionState = MutableStateFlow<Map<String, Any>?>(null)
    private val deepLinkState = MutableStateFlow<Map<String, Any>>(emptyMap())
    private var installReferrerData: Map<String, Any>? = null
    private var didWaitForDeepLink = false
    private var started = false

    fun start() {
        if (started || BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
            if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
                Log.w(TAG, "AppsFlyer start skipped: APPSFLYER_DEV_KEY is blank")
            }
            return
        }
        started = true

        val appsFlyer = AppsFlyerLib.getInstance()
        appsFlyer.setDebugLog(BuildConfig.ENABLE_APPSFLYER_DEBUG)
        appsFlyer.addPushNotificationDeepLinkPath("af_push_link")
        appsFlyer.addPushNotificationDeepLinkPath("url")
        appsFlyer.subscribeForDeepLink(object : DeepLinkListener {
            override fun onDeepLinking(deepLinkResult: DeepLinkResult) {
                Log.d(TAG, "Deep link status=${deepLinkResult.status}")
                if (deepLinkResult.status != DeepLinkResult.Status.FOUND) {
                    return
                }
                val clickEvent = deepLinkResult.deepLink?.clickEvent ?: return
                mergeDeepLinkData("UDL", jsonObjectToMap(clickEvent))
            }
        })
        appsFlyer.init(
            BuildConfig.APPSFLYER_DEV_KEY,
            object : AppsFlyerConversionListener {
                override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                    val conversionData = data?.toMap() ?: emptyMap()
                    conversionState.value = conversionData
                    mergeDeepLinkData(
                        source = "conversion",
                        newData = extractPotentialDeepLinkData(conversionData),
                    )
                    Log.d(
                        TAG,
                        "Conversion success status=${data?.get("af_status")} " +
                                "message=${data?.get("af_message")} " +
                                "mediaSource=${data?.get("media_source")} " +
                                "campaign=${data?.get("campaign")} " +
                                "keys=${conversionState.value?.keys.orEmpty()}",
                    )
                }

                override fun onConversionDataFail(errorMessage: String?) {
                    Log.w(TAG, "Conversion failure=$errorMessage")
                }

                override fun onAppOpenAttribution(attributionData: MutableMap<String, String>?) {
                    val deepLinkData = attributionData
                        ?.mapValues { (_, value) -> value as Any }
                        .orEmpty()
                    mergeDeepLinkData("app_open_attribution", deepLinkData)
                    Log.d(
                        TAG,
                        "App open attribution keys=${attributionData?.keys?.toList().orEmpty()}",
                    )
                }

                override fun onAttributionFailure(errorMessage: String?) {
                    Log.w(TAG, "Attribution failure=$errorMessage")
                }
            },
            application.applicationContext,
        )
        Log.d(TAG, "Starting AppsFlyer SDK")
        appsFlyer.start(
            application,
            BuildConfig.APPSFLYER_DEV_KEY,
            object : AppsFlyerRequestListener {
                override fun onSuccess() {
                    Log.d(TAG, "AppsFlyer launch sent successfully")
                }

                override fun onError(errorCode: Int, errorDesc: String) {
                    Log.w(TAG, "AppsFlyer launch failed code=$errorCode desc=$errorDesc")
                }
            },
        )
    }

    fun performOnDeepLinking(intent: Intent, context: Context) {
        mergeDeepLinkData("intent", extractIntentDeepLinkData(intent))

        if (!started) {
            return
        }
        AppsFlyerLib.getInstance().performOnDeepLinking(intent, context)
    }

    fun sendPushNotificationData(activity: Activity) {
        if (!started || activity.intent.extras == null) {
            return
        }

        runCatching {
            AppsFlyerLib.getInstance().sendPushNotificationData(activity)
        }.onSuccess {
            Log.d(TAG, "Push notification data sent to AppsFlyer")
        }.onFailure { exception ->
            Log.w(TAG, "Failed to send push notification data to AppsFlyer", exception)
        }
    }

    fun updateServerUninstallToken(token: String) {
        if (!started || token.isBlank()) {
            return
        }

        runCatching {
            AppsFlyerLib.getInstance().updateServerUninstallToken(
                application.applicationContext,
                token,
            )
        }.onSuccess {
            Log.d(TAG, "FCM token sent to AppsFlyer")
        }.onFailure { exception ->
            Log.w(TAG, "Failed to send FCM token to AppsFlyer", exception)
        }
    }

    suspend fun awaitLaunchData(): AppsFlyerLaunchData {
        if (BuildConfig.APPSFLYER_DEV_KEY.isBlank()) {
            return AppsFlyerLaunchData()
        }

        val afId = runCatching {
            AppsFlyerLib.getInstance().getAppsFlyerUID(application.applicationContext)
        }.getOrNull().orEmpty()

        var conversionData = awaitNonEmptyConversionData(
            timeoutMillis = INITIAL_CONVERSION_TIMEOUT_MILLIS,
        )

        if (conversionData.isEmpty()) {
            Log.d(TAG, "No conversion callback data yet, trying GCD install data fetch")
            val recoveredConversion = fetchOrganicRetry(afId)
            if (!recoveredConversion.isNullOrEmpty()) {
                conversionData = recoveredConversion
                conversionState.value = recoveredConversion
                mergeDeepLinkData(
                    source = "gcd",
                    newData = extractPotentialDeepLinkData(recoveredConversion),
                )
                Log.d(TAG, "GCD fallback returned keys=${recoveredConversion.keys}")
            }
        }

        if (conversionData.isEmpty()) {
            Log.d(TAG, "Still no conversion data, waiting briefly for a late callback")
            conversionData = awaitNonEmptyConversionData(
                timeoutMillis = LATE_CONVERSION_TIMEOUT_MILLIS,
            )
        }

        if (conversionData["af_status"]?.toString().equals("Organic", ignoreCase = true)) {
            Log.d(TAG, "Received Organic status, retrying GCD after attribution settles")
            delay(ORGANIC_RETRY_DELAY_MILLIS)
            val refreshedOrganic = fetchOrganicRetry(afId)
            if (!refreshedOrganic.isNullOrEmpty()) {
                conversionData = refreshedOrganic
                conversionState.value = refreshedOrganic
                mergeDeepLinkData(
                    source = "organic_retry",
                    newData = extractPotentialDeepLinkData(refreshedOrganic),
                )
                Log.d(TAG, "Organic retry returned keys=${refreshedOrganic.keys}")
            }
        }

        val deepLinkData = awaitDeepLinkDataWithFallback()

        Log.d(
            TAG,
            "Launch data ready afIdPresent=${afId.isNotBlank()} " +
                    "status=${conversionData["af_status"]} " +
                    "mediaSource=${conversionData["media_source"]} " +
                    "campaign=${conversionData["campaign"]} " +
                    "conversionKeys=${conversionData.keys} deepLinkKeys=${deepLinkData.keys}",
        )
        return AppsFlyerLaunchData(
            conversionData = conversionData,
            deepLinkData = deepLinkData,
            afId = afId,
        )
    }

    private suspend fun awaitNonEmptyConversionData(timeoutMillis: Long): Map<String, Any> {
        return withTimeoutOrNull(timeoutMillis) {
            conversionState
                .filterNotNull()
                .first { it.isNotEmpty() }
        } ?: emptyMap()
    }

    private suspend fun awaitDeepLinkDataWithFallback(): Map<String, Any> {
        var deepLinkData = deepLinkState.value

        if (deepLinkData.isEmpty() && !didWaitForDeepLink) {
            didWaitForDeepLink = true
            deepLinkData = withTimeoutOrNull(DEEP_LINK_TIMEOUT_MILLIS) {
                deepLinkState.first { it.isNotEmpty() }
            } ?: emptyMap()
        }

        if (deepLinkData.isEmpty()) {
            val referrerData = installReferrerData ?: fetchInstallReferrerDeepLinkData().orEmpty()
            installReferrerData = referrerData
            if (referrerData.isNotEmpty()) {
                mergeDeepLinkData("install_referrer", referrerData)
                deepLinkData = deepLinkState.value
            }
        }

        return deepLinkData
    }

    private suspend fun fetchOrganicRetry(afId: String): Map<String, Any>? {
        if (afId.isBlank()) {
            return null
        }

        return withContext(Dispatchers.IO) {
            val endpoint = buildString {
                append("https://gcdsdk.appsflyer.com/install_data/v4.0/")
                append(URLEncoder.encode(application.packageName, Charsets.UTF_8.name()))
                append("?devkey=")
                append(URLEncoder.encode(BuildConfig.APPSFLYER_DEV_KEY, Charsets.UTF_8.name()))
                append("&device_id=")
                append(URLEncoder.encode(afId, Charsets.UTF_8.name()))
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            try {
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    return@withContext null
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                if (response.isBlank()) {
                    return@withContext null
                }
                jsonObjectToMap(JSONObject(response))
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun fetchInstallReferrerDeepLinkData(): Map<String, Any>? {
        return withTimeoutOrNull(INSTALL_REFERRER_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val client = InstallReferrerClient
                    .newBuilder(application.applicationContext)
                    .build()

                continuation.invokeOnCancellation {
                    runCatching { client.endConnection() }
                }

                runCatching {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            val result = if (
                                responseCode == InstallReferrerClient.InstallReferrerResponse.OK
                            ) {
                                runCatching {
                                    val rawReferrer = client.installReferrer.installReferrer.orEmpty()
                                    val parsedReferrer = parseQueryLike(rawReferrer).toMutableMap()
                                    if (rawReferrer.isNotBlank()) {
                                        parsedReferrer["install_referrer"] = rawReferrer
                                    }
                                    parsedReferrer.toMap()
                                }.getOrDefault(emptyMap())
                            } else {
                                emptyMap()
                            }

                            runCatching { client.endConnection() }
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                        override fun onInstallReferrerServiceDisconnected() = Unit
                    })
                }.onFailure { exception ->
                    Log.w(TAG, "Install referrer connection failed", exception)
                    runCatching { client.endConnection() }
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun mergeDeepLinkData(source: String, newData: Map<String, Any>) {
        val normalizedData = newData
            .mapValuesNotNull { (_, value) -> normalizeAttributionValue(value) }

        if (normalizedData.isEmpty()) {
            return
        }

        val merged = LinkedHashMap<String, Any>()
        merged.putAll(deepLinkState.value)
        merged.putAll(normalizedData)
        deepLinkState.value = merged
        Log.d(TAG, "$source deep link payload keys=${merged.keys}")
    }

    private fun extractPotentialDeepLinkData(data: Map<String, Any>): Map<String, Any> {
        return data
            .filterKeys { key -> isPotentialDeepLinkKey(key) }
            .mapValuesNotNull { (_, value) -> normalizeAttributionValue(value) }
    }

    private fun extractIntentDeepLinkData(intent: Intent): Map<String, Any> {
        val result = linkedMapOf<String, Any>()

        intent.dataString?.takeIf { it.isNotBlank() }?.let { dataString ->
            result["deep_link_url"] = dataString
            result.putAll(parseQueryLike(dataString))
        }

        intent.extras?.let { extras ->
            bundleToMap(extras)
                .filterKeys { key -> isPotentialDeepLinkKey(key) }
                .forEach { (key, value) -> result[key] = value }
        }

        return result
    }

    private fun bundleToMap(bundle: Bundle): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        for (key in bundle.keySet()) {
            val value = bundle.get(key) ?: continue
            normalizeAttributionValue(value)?.let { result[key] = it }
        }
        return result
    }

    private fun parseQueryLike(rawValue: String): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        val candidates = linkedSetOf(rawValue)
        urlDecode(rawValue)?.let { decoded -> candidates += decoded }

        candidates.forEach { candidate ->
            val query = candidate
                .substringAfter('?', candidate)
                .substringBefore('#')

            query.split('&')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { parameter ->
                    val separatorIndex = parameter.indexOf('=')
                    if (separatorIndex <= 0) {
                        return@forEach
                    }

                    val key = urlDecode(parameter.substring(0, separatorIndex))
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val value = urlDecode(parameter.substring(separatorIndex + 1))
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                    result[key] = value
                }
        }

        return result
    }

    private fun isPotentialDeepLinkKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return normalized in DIRECT_DEEP_LINK_KEYS ||
                normalized.startsWith("af_sub") ||
                normalized.startsWith("af_ad") ||
                normalized.startsWith("af_channel") ||
                normalized.startsWith("af_c_id") ||
                normalized.startsWith("af_siteid") ||
                normalized.startsWith("deep_link") ||
                normalized.startsWith("utm_") ||
                normalized.startsWith("sub_id_")
    }

    private fun normalizeAttributionValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is Number, is Boolean -> value
            is JSONObject -> jsonObjectToMap(value).takeIf { it.isNotEmpty() }
            is Map<*, *> -> value.entries
                .mapNotNull { entry ->
                    val key = entry.key?.toString() ?: return@mapNotNull null
                    val normalizedValue = normalizeAttributionValue(entry.value)
                        ?: return@mapNotNull null
                    key to normalizedValue
                }
                .toMap()
                .takeIf { it.isNotEmpty() }

            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun urlDecode(value: String): String? {
        return runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrNull()
    }
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(
    transform: (Map.Entry<K, V>) -> R?,
): Map<K, R> {
    val result = LinkedHashMap<K, R>()
    forEach { entry ->
        transform(entry)?.let { result[entry.key] = it }
    }
    return result
}