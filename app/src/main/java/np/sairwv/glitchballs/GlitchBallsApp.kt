package np.sairwv.glitchballs

import android.app.Application
import android.util.Log
import np.sairwv.glitchballs.launch.AppMode
import np.sairwv.glitchballs.launch.AppsFlyerBridge
import np.sairwv.glitchballs.launch.ConfigDecision
import np.sairwv.glitchballs.launch.ConfigRepository
import np.sairwv.glitchballs.launch.LaunchPreferences
import np.sairwv.glitchballs.launch.LaunchResolver
import np.sairwv.glitchballs.web.GlitchMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlitchBallsApp : Application() {

    companion object {
        private const val TAG = "GlitchBallsApp"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var launchPreferences: LaunchPreferences
        private set

    lateinit var appsFlyerBridge: AppsFlyerBridge
        private set

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var launchResolver: LaunchResolver
        private set

    override fun onCreate() {
        super.onCreate()

        launchPreferences = LaunchPreferences(this)
        appsFlyerBridge = AppsFlyerBridge(this)
        configRepository = ConfigRepository(this, launchPreferences)
        launchResolver = LaunchResolver(
            this,
            launchPreferences,
            appsFlyerBridge,
            configRepository,
        )

        GlitchMessagingService.ensureChannel(this)
        appsFlyerBridge.start()
        appScope.launch {
            if (configRepository.refreshPushTokenCache()) {
                appsFlyerBridge.updateServerUninstallToken(launchPreferences.pushToken)
            }
        }
    }

    fun syncConfigForUpdatedPushToken() {
        if (launchPreferences.mode != AppMode.WEBVIEW) {
            logSkippedPushTokenSync()
            return
        }

        appScope.launch {
            syncConfigForUpdatedPushTokenNow()
        }
    }

    suspend fun syncConfigForUpdatedPushTokenNow(): Boolean = withContext(Dispatchers.IO) {
        if (launchPreferences.mode != AppMode.WEBVIEW) {
            logSkippedPushTokenSync()
            return@withContext false
        }

        val hasPushToken = configRepository.awaitPushTokenCache()
        if (hasPushToken) {
            appsFlyerBridge.updateServerUninstallToken(launchPreferences.pushToken)
        }
        Log.d(TAG, "Syncing config for push token. hasPushToken=$hasPushToken")
        when (val decision = configRepository.requestConfig(appsFlyerBridge.awaitLaunchData())) {
            is ConfigDecision.Success -> {
                launchPreferences.cacheWebTarget(decision.url, decision.expiresAtMillis)
                true
            }

            is ConfigDecision.Rejected,
            is ConfigDecision.Failure,
            -> false
        }
    }

    private fun logSkippedPushTokenSync() {
        Log.d(
            TAG,
            "Skipping push-token config sync until WEBVIEW mode is established. " +
                "Current mode=${launchPreferences.mode}",
        )
    }
}
