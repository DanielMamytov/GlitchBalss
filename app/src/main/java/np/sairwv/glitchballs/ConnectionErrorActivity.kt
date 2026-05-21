package np.sairwv.glitchballs

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import np.sairwv.glitchballs.databinding.ActivityConnectionErrorBinding
import np.sairwv.glitchballs.launch.NetworkStatus

class ConnectionErrorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GlitchConnectionError"
    }

    private lateinit var binding: ActivityConnectionErrorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityConnectionErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.retryButton.setOnClickListener {
            retryLaunch()
        }

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

    private fun retryLaunch() {
        if (!NetworkStatus.hasValidatedInternet(this)) {
            Log.d(TAG, "Retry tapped while connection is still unavailable")
            binding.stateTitle.text = getString(R.string.connection_error_title)
            binding.stateBody.text = getString(R.string.connection_error_body)
            return
        }

        Log.d(TAG, "Connection restored, retrying launch")
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                intent.extras?.let { putExtras(it) }
                data = intent.data
                action = intent.action
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}