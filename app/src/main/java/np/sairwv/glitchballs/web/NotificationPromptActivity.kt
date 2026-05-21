package np.sairwv.glitchballs.web

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import np.sairwv.glitchballs.AppIntents
import np.sairwv.glitchballs.GlitchBallsApp
import np.sairwv.glitchballs.databinding.ActivityNotificationPromptBinding

class NotificationPromptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationPromptBinding

    private val app by lazy { application as GlitchBallsApp }


    private val requestNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            app.launchPreferences.markNotificationPromptCompleted()
            openWebView()
            app.syncConfigForUpdatedPushToken()
        } else {
            val canRequestAgain = canRequestNotificationsLaterAfterSystemDenial()

            app.launchPreferences.markNotificationPermissionDenied(
                canRequestSystemDialogAgain = canRequestAgain,
            )

            openWebView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityNotificationPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.getStringExtra(AppIntents.EXTRA_WEB_URL).isNullOrBlank()) {
            finish()
            return
        }

        if (areNotificationsAlreadyAllowed()) {
            app.launchPreferences.markNotificationPromptCompleted()
            openWebView()
            app.syncConfigForUpdatedPushToken()
            return
        }

        if (isSystemNotificationRequestUnavailable()) {
            app.launchPreferences.markNotificationPermissionUnavailable()
            openWebView()
            return
        }

        binding.allowButton.setOnClickListener {
            requestNotificationsAccess()
        }

        binding.skipButton.setOnClickListener {
            deferPromptAndOpenWebView()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                deferPromptAndOpenWebView()
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.promptScroll.updatePadding(
                left = systemBars.left + dp(20),
                top = systemBars.top + dp(24),
                right = systemBars.right + dp(20),
                bottom = systemBars.bottom + dp(24),
            )

            insets
        }
    }


    private fun deferPromptAndOpenWebView() {
        app.launchPreferences.deferNotificationPrompt()
        openWebView()
    }

    private fun requestNotificationsAccess() {
        if (areNotificationsAlreadyAllowed()) {
            app.launchPreferences.markNotificationPromptCompleted()
            openWebView()
            app.syncConfigForUpdatedPushToken()
            return
        }

        if (isSystemNotificationRequestUnavailable()) {
            app.launchPreferences.markNotificationPermissionUnavailable()
            openWebView()
            return
        }

        requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun areNotificationsAlreadyAllowed(): Boolean {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return true
        }

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSystemNotificationRequestUnavailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (app.launchPreferences.isNotificationPermissionRequestLocked()) {
            return true
        }

        return false
    }

    private fun canRequestNotificationsLaterAfterSystemDenial(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        if (app.launchPreferences.isNotificationPermissionRequestLocked()) {
            return false
        }

        val previousDenials = app.launchPreferences.notificationPermissionDenialCount

        if (previousDenials <= 0) {
            return true
        }

        return shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openWebView(updatedUrl: String? = null) {
        startActivity(
            Intent(this, WebViewActivity::class.java).apply {
                putExtras(intent)

                if (!updatedUrl.isNullOrBlank()) {
                    putExtra(AppIntents.EXTRA_WEB_URL, updatedUrl)
                }
            },
        )

        finish()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}