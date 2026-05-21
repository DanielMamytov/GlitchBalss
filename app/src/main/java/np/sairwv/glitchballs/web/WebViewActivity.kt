package np.sairwv.glitchballs.web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import np.sairwv.glitchballs.AppIntents
import np.sairwv.glitchballs.databinding.ActivityWebViewBinding
import java.io.File

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebViewBinding

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private var lastRequestedUrl: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult

        val uris = if (result.resultCode == RESULT_OK) {
            extractResultUris(result.data)
        } else {
            null
        }

        callback.onReceiveValue(uris)
        fileChooserCallback = null
        cameraCaptureUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        configureFullscreenWindow()

        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyFullBleedInsets()
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                }
            }
        })

        configureWebView()

        if (savedInstanceState != null) {
            val webBundle = savedInstanceState.getBundle(AppIntents.STATE_WEBVIEW)

            if (webBundle != null) {
                binding.webView.restoreState(webBundle)
                lastRequestedUrl = binding.webView.url
            } else {
                loadFromIntent(intent, force = false)
            }
        } else {
            loadFromIntent(intent, force = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hideSystemBars()
        loadFromIntent(intent, force = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureFullscreenWindow()
        binding.root.requestApplyInsets()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        binding.webView.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onPause() {
        binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val webViewState = Bundle()
        binding.webView.saveState(webViewState)
        outState.putBundle(AppIntents.STATE_WEBVIEW, webViewState)
    }

    private fun applyFullBleedInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            val displayCutout = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.displayCutout(),
            )

            binding.webViewHost.updatePadding(
                left = displayCutout.left,
                top = maxOf(systemBars.top, displayCutout.top),
                right = displayCutout.right,
                bottom = maxOf(systemBars.bottom, displayCutout.bottom),
            )
            hideSystemBars()
            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.setBackgroundColor(Color.BLACK)
        binding.webView.isVerticalScrollBarEnabled = false
        binding.webView.isHorizontalScrollBarEnabled = false

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            userAgentString = sanitizeUserAgent(
                WebSettings.getDefaultUserAgent(this@WebViewActivity),
            )
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    return
                }

                val grantedResources = request.resources.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                }

                if (grantedResources.isNotEmpty()) {
                    request.grant(grantedResources.toTypedArray())
                } else {
                    request.deny()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback ?: return false

                return runCatching {
                    fileChooserLauncher.launch(buildChooserIntent(fileChooserParams))
                    true
                }.getOrElse {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest,
            ): Boolean {
                if (request.isForMainFrame) {
                    lastRequestedUrl = request.url.toString()
                }

                hideSystemBars()
                return handleExternalSchemes(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (url != null) {
                    lastRequestedUrl = url
                }
                hideSystemBars()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                hideSystemBars()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)

                if (!request.isForMainFrame) {
                    return
                }

                val description = error?.description?.toString().orEmpty()

                val redirectLoop = error?.errorCode == ERROR_REDIRECT_LOOP ||
                        description.contains("ERR_TOO_MANY_REDIRECTS", ignoreCase = true)

                if (redirectLoop) {
                    val recoveryUrl = request.url.toString().ifBlank {
                        lastRequestedUrl.orEmpty()
                    }

                    if (recoveryUrl.isNotBlank()) {
                        view?.post {
                            view.loadUrl(recoveryUrl)
                        }
                        return
                    }
                }

            }
        }
    }

    private fun buildChooserIntent(fileChooserParams: WebChromeClient.FileChooserParams?): Intent {
        val mimeType = fileChooserParams?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "*/*"

        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )
        }

        val cameraIntent = createCameraIntent()

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pickIntent)

            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }
    }

    private fun createCameraIntent(): Intent? {
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        if (captureIntent.resolveActivity(packageManager) == null) {
            return null
        }

        val uploadsDirectory = File(cacheDir, "webview-uploads").apply {
            mkdirs()
        }

        val outputFile = File.createTempFile("upload_", ".jpg", uploadsDirectory)

        val outputUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            outputFile,
        )

        cameraCaptureUri = outputUri

        return captureIntent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun extractResultUris(data: Intent?): Array<Uri>? {
        val clipData = data?.clipData

        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri
            }
        }

        data?.data?.let { uri ->
            return arrayOf(uri)
        }

        cameraCaptureUri?.let { uri ->
            return arrayOf(uri)
        }

        return null
    }

    private fun loadFromIntent(intent: Intent, force: Boolean) {
        val targetUrl = intent.getStringExtra(AppIntents.EXTRA_WEB_URL)

        if (targetUrl.isNullOrBlank()) {
            finish()
            return
        }

        if (force || binding.webView.url != targetUrl) {
            binding.webView.loadUrl(targetUrl)
        }
    }

    private fun handleExternalSchemes(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()

        if (scheme == "http" || scheme == "https") {
            return false
        }

        return runCatching {
            if (scheme == "intent") {
                val parsedIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")

                if (!fallbackUrl.isNullOrBlank()) {
                    binding.webView.loadUrl(fallbackUrl)
                    true
                } else {
                    startActivity(parsedIntent)
                    true
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }
        }.getOrDefault(true)
    }

    private fun configureFullscreenWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allowContentIntoDisplayCutout()

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun allowContentIntoDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }


    private fun sanitizeUserAgent(defaultUserAgent: String): String {
        return defaultUserAgent
            .replace("; wv", "")
            .replace(" Version/4.0", "")
    }
}
