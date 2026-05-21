package np.sairwv.glitchballs.launch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import np.sairwv.glitchballs.BuildConfig
import kotlinx.coroutines.delay

class LaunchResolver(
    private val context: Context,
    private val preferences: LaunchPreferences,
    private val appsFlyerBridge: AppsFlyerBridge,
    private val configRepository: ConfigRepository,
) {

    companion object {
        private const val TAG = "GlitchLaunch"
    }

    suspend fun resolve(notificationUrl: String?): LaunchDecision {
        resolveDebugOverride(notificationUrl)?.let { return it }

        Log.d(
            TAG,
            "Resolving launch mode=${preferences.mode} " +
                    "notificationUrl=${notificationUrl ?: "<none>"} " +
                    "cachedUrl=${preferences.cachedWebUrl ?: "<none>"}",
        )

        if (!NetworkStatus.hasValidatedInternet(context)) {
            Log.w(TAG, "Launch aborted: no validated internet")
            return LaunchDecision.ShowOfflineRetry
        }

        return when (preferences.mode) {
            AppMode.GAME -> LaunchDecision.OpenGame
            AppMode.WEBVIEW -> resolveWebMode(notificationUrl)
            AppMode.UNDECIDED -> resolveFirstDecision(notificationUrl)
        }
    }

    private fun resolveDebugOverride(notificationUrl: String?): LaunchDecision? {
        if (!BuildConfig.DEBUG || !BuildConfig.FORCE_DEBUG_WEB_FLOW) {
            return null
        }

        val fallbackUrl = BuildConfig.DEBUG_WEB_URL.takeIf { it.isNotBlank() } ?: return null
        val targetUrl = notificationUrl ?: fallbackUrl
        preferences.mode = AppMode.WEBVIEW
        preferences.cacheWebTarget(fallbackUrl, Long.MAX_VALUE)
        Log.d(TAG, "Using debug web override url=$targetUrl")
        return LaunchDecision.OpenWeb(
            url = targetUrl,
            needsNotificationPrompt = shouldShowNotificationPrompt(),
            isEphemeralUrl = notificationUrl != null,
        )
    }

    private suspend fun resolveWebMode(notificationUrl: String?): LaunchDecision {
        if (!notificationUrl.isNullOrBlank()) {
            Log.d(TAG, "Web mode: opening one-time notification url")
            return openWebDecision(
                url = notificationUrl,
                isEphemeralUrl = true,
            )
        }

        val cachedUrl = preferences.cachedWebUrl?.takeIf { it.isNotBlank() }
        val freshCachedUrl = cachedUrl.takeIf { preferences.isCachedWebTargetFresh() }
        val networkAvailable = NetworkStatus.hasValidatedInternet(context)
        Log.d(
            TAG,
            "Web mode network=$networkAvailable cachedUrl=${cachedUrl ?: "<none>"} " +
                    "freshCachedUrl=${freshCachedUrl ?: "<none>"}",
        )

        if (!networkAvailable) {
            Log.w(TAG, "Web mode aborted: no validated internet")
            return LaunchDecision.ShowOfflineRetry
        }

        if (!freshCachedUrl.isNullOrBlank()) {
            Log.d(TAG, "Web mode: cached url is fresh, opening without config request")
            return openWebDecision(
                url = freshCachedUrl,
                isEphemeralUrl = false,
            )
        }

        val configDecision = configRepository.requestConfig(appsFlyerBridge.awaitLaunchData())
        Log.d(TAG, "Web mode configResult=${configDecision.javaClass.simpleName}")

        return when (configDecision) {
            is ConfigDecision.Success -> {
                preferences.cacheWebTarget(configDecision.url, configDecision.expiresAtMillis)
                openWebDecision(
                    url = configDecision.url,
                    isEphemeralUrl = false,
                )
            }

            is ConfigDecision.Rejected -> {
                if (!cachedUrl.isNullOrBlank()) {
                    Log.w(TAG, "Web mode: config rejected, opening last cached url")
                    openWebDecision(
                        url = cachedUrl,
                        isEphemeralUrl = false,
                    )
                } else {
                    Log.w(TAG, "Web mode: config rejected and no cached url, switching to GAME")
                    preferences.mode = AppMode.GAME
                    LaunchDecision.OpenGame
                }
            }

            is ConfigDecision.Failure -> {
                if (!cachedUrl.isNullOrBlank()) {
                    Log.w(TAG, "Web mode: config failed, opening last cached url")
                    openWebDecision(
                        url = cachedUrl,
                        isEphemeralUrl = false,
                    )
                } else {
                    Log.w(TAG, "Web mode: config failed and no cached url: ${configDecision.reason}")
                    LaunchDecision.ShowOfflineRetry
                }
            }
        }
    }

    private suspend fun resolveFirstDecision(notificationUrl: String?): LaunchDecision {
        if (!NetworkStatus.hasValidatedInternet(context)) {
            Log.w(TAG, "First decision aborted: no validated internet")
            return LaunchDecision.ShowOfflineRetry
        }

        var launchData = appsFlyerBridge.awaitLaunchData()
        var configDecision = configRepository.requestConfig(launchData)
        if (shouldRetryFirstDecision(launchData, configDecision)) {
            Log.d(TAG, "Retrying first decision after waiting for late AppsFlyer data")
            delay(1_500L)
            launchData = appsFlyerBridge.awaitLaunchData()
            configDecision = configRepository.requestConfig(launchData)
        }
        Log.d(TAG, "First decision configResult=${configDecision.javaClass.simpleName}")
        return when (configDecision) {
            is ConfigDecision.Success -> {
                preferences.mode = AppMode.WEBVIEW
                preferences.cacheWebTarget(configDecision.url, configDecision.expiresAtMillis)
                Log.d(TAG, "Switching to WEBVIEW url=${configDecision.url}")
                openWebDecision(
                    url = notificationUrl ?: configDecision.url,
                    isEphemeralUrl = notificationUrl != null,
                )
            }

            is ConfigDecision.Rejected -> {
                preferences.mode = AppMode.GAME
                Log.w(TAG, "Switching to GAME because config rejected: ${configDecision.message}")
                LaunchDecision.OpenGame
            }

            is ConfigDecision.Failure -> {
                Log.w(TAG, "Keeping mode undecided because config failed: ${configDecision.reason}")
                LaunchDecision.ShowOfflineRetry
            }
        }
    }

    private fun openWebDecision(
        url: String,
        isEphemeralUrl: Boolean,
    ): LaunchDecision.OpenWeb {
        return LaunchDecision.OpenWeb(
            url = url,
            needsNotificationPrompt = shouldShowNotificationPrompt(),
            isEphemeralUrl = isEphemeralUrl,
        )
    }

    private fun shouldShowNotificationPrompt(): Boolean {
        if (!preferences.shouldShowNotificationPrompt()) {
            return false
        }

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            preferences.markNotificationPromptCompleted()
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            preferences.markNotificationPromptCompleted()
            return false
        }

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun shouldRetryFirstDecision(
        launchData: AppsFlyerLaunchData,
        configDecision: ConfigDecision,
    ): Boolean {
        return configDecision !is ConfigDecision.Success &&
                launchData.conversionData.isEmpty() &&
                launchData.deepLinkData.isEmpty()
    }
}