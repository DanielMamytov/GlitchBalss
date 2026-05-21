package np.sairwv.glitchballs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import np.sairwv.glitchballs.databinding.ActivityMainBinding
import np.sairwv.glitchballs.launch.LaunchDecision
import np.sairwv.glitchballs.launch.NetworkStatus
import np.sairwv.glitchballs.ui.activity.GameActivity
import np.sairwv.glitchballs.web.NotificationPromptActivity
import np.sairwv.glitchballs.web.WebViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GlitchMain"
    }

    private lateinit var binding: ActivityMainBinding

    private val app by lazy { application as GlitchBallsApp }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var launchJob: Job? = null
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app.appsFlyerBridge.sendPushNotificationData(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.contentCard.updatePadding(
                left = dp(24),
                top = systemBars.top + dp(24),
                right = dp(24),
                bottom = systemBars.bottom + dp(24),
            )

            insets
        }
    }

    override fun onResume() {
        super.onResume()

        app.appsFlyerBridge.performOnDeepLinking(intent, this)

        if (!hasNavigated) {
            startResolution()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        app.appsFlyerBridge.sendPushNotificationData(this)
        app.appsFlyerBridge.performOnDeepLinking(intent, this)

        hasNavigated = false
        startResolution()
    }

    override fun onDestroy() {
        launchJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startResolution() {
        launchJob?.cancel()

        if (!NetworkStatus.hasValidatedInternet(this)) {
            openConnectionError()
            return
        }

        launchJob = activityScope.launch {
            val notificationUrl = extractNotificationUrl(intent)

            when (val decision = app.launchResolver.resolve(notificationUrl)) {
                LaunchDecision.OpenGame -> openGame()
                is LaunchDecision.OpenWeb -> openWeb(decision)
                LaunchDecision.ShowOfflineRetry -> openConnectionError()
            }
        }
    }

    private fun openGame() {
        hasNavigated = true

        Log.d(TAG, "Opening native game")

        startActivity(Intent(this, GameActivity::class.java))
        finish()
    }

    private fun openWeb(decision: LaunchDecision.OpenWeb) {
        hasNavigated = true

        app.syncConfigForUpdatedPushToken()

        Log.d(
            TAG,
            "Opening web url=${decision.url} " +
                    "needsNotificationPrompt=${decision.needsNotificationPrompt} " +
                    "isEphemeralUrl=${decision.isEphemeralUrl}",
        )

        val targetIntent = if (decision.needsNotificationPrompt) {
            Intent(this, NotificationPromptActivity::class.java)
        } else {
            Intent(this, WebViewActivity::class.java)
        }.apply {
            putExtra(AppIntents.EXTRA_WEB_URL, decision.url)
            putExtra(AppIntents.EXTRA_IS_EPHEMERAL_URL, decision.isEphemeralUrl)
        }

        startActivity(targetIntent)
        finish()
    }

    private fun openConnectionError() {
        hasNavigated = true

        startActivity(
            Intent(this, ConnectionErrorActivity::class.java).apply {
                intent.extras?.let { putExtras(it) }
                data = intent.data
                action = intent.action
            },
        )

        finish()
    }

    private fun extractNotificationUrl(intent: Intent): String? {
        val url = intent.getStringExtra(AppIntents.EXTRA_NOTIFICATION_URL)
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("af_push_link")
            ?: intent.getStringExtra("link")
            ?: intent.getStringExtra("deep_link")
            ?: intent.extras?.getString("url")
            ?: intent.extras?.getString("af_push_link")
            ?: intent.extras?.getString("link")
            ?: intent.extras?.getString("deep_link")

        if (!url.isNullOrBlank()) {
            return url
        }

        return intent.dataString
            ?.takeIf { shouldUseIntentDataAsNotificationUrl(intent, it) }
    }

    private fun shouldUseIntentDataAsNotificationUrl(intent: Intent, url: String): Boolean {
        if (url.isBlank()) {
            return false
        }

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return false
        }

        return intent.action != Intent.ACTION_VIEW || !uri.hasAppsFlyerDeepLinkMarkers()
    }

    private fun Uri.hasAppsFlyerDeepLinkMarkers(): Boolean {
        val host = host.orEmpty()
        if (host.contains("onelink", ignoreCase = true)) {
            return true
        }

        val queryNames = runCatching {
            queryParameterNames.map { it.lowercase() }
        }.getOrDefault(emptyList())

        return queryNames.any { parameter ->
            parameter == "c" ||
                    parameter == "pid" ||
                    parameter.startsWith("af_") ||
                    parameter.startsWith("deep_link")
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}