package com.iipauk.spider

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.SafeBrowsingResponse
import android.webkit.RenderProcessGoneDetail
import android.net.http.SslError
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** Independent internet WebView. Intentionally has NO native JavaScript bridge. */
class SpiderBrowserActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var browserDestroyed = false
    private var pageLoadError: String? = null
    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingDownload
        pendingDownload = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "Загрузка отменена: нет разрешения на папку Downloads", Toast.LENGTH_LONG).show()
    }
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback != null) {
            val selected = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else null
            callback.onReceiveValue(selected)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF100E17.toInt()
        window.navigationBarColor = 0xFF100E17.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)); setBackgroundColor(0xFF100E17.toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            hint = "https://example.com"
            singleLine = true
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFAAA5B8.toInt())
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(0xFF24212D.toInt())
        }
        val go = button("Открыть") { loadAddress() }
        row.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(go, LinearLayout.LayoutParams(dp(92), dp(48)))
        root.addView(row)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(button("←") { if (::browser.isInitialized && browser.canGoBack()) browser.goBack() })
        controls.addView(button("↻") { if (::browser.isInitialized) browser.reload() })
        controls.addView(button("⌂") { loadUrl(DEFAULT_URL) })
        status = TextView(this).apply { text = "Spider Browser · прямой HTTPS/HTTP доступ"; setTextColor(0xFFC9C2D7.toInt()); textSize = 12f; setPadding(dp(8), 0, 0, 0) }
        controls.addView(status, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(controls)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        browser = WebView(this)
        with(browser.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (pageLoadError != null) {
                    status.text = pageLoadError
                } else if (newProgress < 100) {
                    status.text = "Загрузка $newProgress%"
                } else {
                    status.text = view.title?.takeIf { it.isNotBlank() } ?: "Готово"
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@SpiderBrowserActivity, "На устройстве не найден выбор файлов", Toast.LENGTH_SHORT).show()
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@SpiderBrowserActivity, "Не удалось открыть выбор файлов", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (SafeUrlPolicy.parse(uri.toString()) == null) {
                    status.text = "Заблокирована небезопасная схема URL"
                    Toast.makeText(this@SpiderBrowserActivity, "Разрешены только HTTP и HTTPS", Toast.LENGTH_SHORT).show()
                    return true
                }
                address.setText(uri.toString())
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoadError = null
                address.setText(url)
                status.text = "Подключение…"
            }
            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                status.text = pageLoadError ?: (view.title?.takeIf { it.isNotBlank() } ?: url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageLoadError = "Не удалось загрузить страницу: ${error.description}"
                    status.text = pageLoadError!!
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    pageLoadError = "Сайт вернул HTTP ${response.statusCode}"
                    status.text = pageLoadError!!
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Do not bypass TLS validation.
                pageLoadError = "TLS/сертификат не прошёл проверку; соединение отменено"
                status.text = pageLoadError!!
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                callback.backToSafety(true)
                pageLoadError = "Safe Browsing заблокировал потенциально опасную страницу"
                status.text = pageLoadError!!
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // A dead renderer must be removed and destroyed, never reused.
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.webChromeClient = null
                view.webViewClient = null
                view.destroy()
                if (view === browser) browserDestroyed = true
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                Toast.makeText(this@SpiderBrowserActivity, "Вкладка аварийно завершилась. Открой сайт повторно.", Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        browser.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val uri = SafeUrlPolicy.parse(url)
            if (uri == null) {
                Toast.makeText(this, "Загрузка заблокирована: небезопасный URL", Toast.LENGTH_SHORT).show()
            } else {
                val startDownload = {
                    enqueueDownload(uri, userAgent, contentDisposition, mimeType)
                }
                // Public Downloads requires runtime write permission on Android 8.1 and earlier.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDownload = startDownload
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    startDownload()
                }
            }
        })

        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadAddress(); true } else false
        }
        if (savedInstanceState != null && browser.restoreState(savedInstanceState) != null) {
            address.setText(browser.url ?: DEFAULT_URL)
        } else {
            val initial = intent.getStringExtra(MainActivity.EXTRA_BROWSER_URL).orEmpty()
            loadUrl(SafeUrlPolicy.parse(initial)?.toString() ?: DEFAULT_URL)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browser.canGoBack()) browser.goBack() else finish()
            }
        })
    }

    private fun enqueueDownload(uri: Uri, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        runCatching {
            val fileName = safeFileName(contentDisposition, uri)
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription("Загрузка через Spider Browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            // Do not forward WebView session cookies to DownloadManager: redirects
            // are outside this Activity's control. Authenticated-only downloads need
            // a dedicated, redirect-aware downloader in a later milestone.
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Загрузка добавлена в системный менеджер", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_SHORT).show() }
    }

    private fun loadAddress() {
        val parsed = SafeUrlPolicy.addressOrSearch(address.text?.toString().orEmpty())
        if (parsed == null) {
            Toast.makeText(this, "Адрес или поисковый запрос недопустим. Разрешены HTTP и HTTPS без логина/пароля в URL.", Toast.LENGTH_LONG).show()
            return
        }
        loadUrl(parsed.toString())
    }

    private fun loadUrl(url: String) {
        val safe = SafeUrlPolicy.parse(url)
        if (safe == null) {
            status.text = "Адрес заблокирован"
            return
        }
        address.setText(safe.toString())
        browser.loadUrl(safe.toString())
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun safeFileName(contentDisposition: String?, uri: Uri): String {
        val rfc5987 = contentDisposition?.let {
            Regex("""filename\*\s*=\s*(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }?.trim(' ', '"', '\'')?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
        val legacy = contentDisposition?.let {
            Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]*))""", RegexOption.IGNORE_CASE)
                .find(it)?.let { match -> match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) ?: match.groupValues.getOrNull(2) }
        }?.trim(' ', '"', '\'')
        val raw = sequenceOf(rfc5987, legacy, uri.lastPathSegment).firstOrNull { !it.isNullOrBlank() } ?: "spider-download"
        val pathSafe = raw.filter { it.code >= 0x20 && it.code != 0x7f && it != '/' && it != '\\' }
        val clean = pathSafe.replace(Regex("""[^A-Za-z0-9._() -]"""), "_").trim().take(100)
        return clean.takeUnless { it == "." || it == ".." }.orEmpty().ifBlank { "spider-download" }
    }

    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized && !browserDestroyed) browser.onResume()
    }

    override fun onPause() {
        if (::browser.isInitialized && !browserDestroyed) browser.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::browser.isInitialized) browser.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        if (::browser.isInitialized && !browserDestroyed) {
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.stopLoading()
            browser.webChromeClient = null
            browser.webViewClient = null
            browser.destroy()
            browserDestroyed = true
        }
        super.onDestroy()
    }

    companion object { private const val DEFAULT_URL = "https://duckduckgo.com/" }
}
