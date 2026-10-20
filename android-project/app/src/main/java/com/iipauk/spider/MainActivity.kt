package com.iipauk.spider

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var agentRuntime: SpiderAgentRuntime
    private lateinit var assetLoader: WebViewAssetLoader
    private var bridgeReady = false
    private var pendingOverlaySection: String? = null
    private var pendingHtml: String? = null
    private var pendingHtmlFileName: String = "spider-demo.html"
    private var overlayPermissionRequestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agentRuntime = SpiderAgentRuntime.get(applicationContext)
        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureTrustedShell()
        registerBridgeIfSupported()
        pendingOverlaySection = intent.getStringExtra(EXTRA_OVERLAY_SECTION)
        webView.loadUrl(APP_URL)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureTrustedShell() {
        with(webView.settings) {
            javaScriptEnabled = true // Required by the local app UI.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                if (url == APP_URL) {
                    if (bridgeReady) installMinimalFacade()
                    applyPendingOverlaySection()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.toString() == APP_URL) return false
                // Keep the app-assets virtual origin inside the trusted shell only.
                if (uri.scheme == "https" && uri.host == ASSET_HOST) {
                    Toast.makeText(this@MainActivity, "Локальный ресурс приложения нельзя открыть как внешний сайт", Toast.LENGTH_SHORT).show()
                    return true
                }
                val safe = SafeUrlPolicy.parse(uri.toString())
                if (safe != null) {
                    openNativeBrowser(safe)
                } else {
                    Toast.makeText(this@MainActivity, "Переход заблокирован политикой безопасности", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Fail closed; never bypass a certificate error.
            }
        }
    }

    private fun registerBridgeIfSupported() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bridgeReady = false
            return // Fail closed. No addJavascriptInterface fallback.
        }
        try {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_OBJECT,
                setOf(ASSET_ORIGIN)
            ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                if (!isMainFrame || webView.url != APP_URL || sourceOrigin.scheme != "https" || sourceOrigin.host != ASSET_HOST || sourceOrigin.port != -1) {
                    replyProxy.postMessage("{\"ok\":false,\"error\":\"origin_rejected\"}")
                    return@addWebMessageListener
                }
                val payload = if (message.data.length <= MAX_BRIDGE_MESSAGE) {
                    runCatching { JSONObject(message.data) }.getOrNull()
                } else null
                if (payload == null) {
                    replyProxy.postMessage("{\"ok\":false,\"error\":\"invalid_message\"}")
                    return@addWebMessageListener
                }
                val requestId = payload.optString("id", "").take(80)
                when (payload.optString("method")) {
                    "open_browser" -> {
                        val raw = payload.optString("url", DEFAULT_BROWSER_URL)
                        val uri = SafeUrlPolicy.parse(raw)
                        if (uri == null) {
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", false).put("error", "url_rejected").toString())
                        } else {
                            runOnUiThread { openNativeBrowser(uri) }
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                        }
                    }
                    "open_terminal" -> {
                        runOnUiThread { startActivity(Intent(this@MainActivity, SpiderTerminalActivity::class.java)) }
                        replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                    }
                    "open_media" -> {
                        val rawMediaUrl = payload.optString("url", "").takeIf { it.isNotBlank() }
                        val mediaUrl = if (rawMediaUrl == null) null else SpiderMediaUrlPolicy.parseHttpsUrl(rawMediaUrl)
                        if (rawMediaUrl != null && mediaUrl == null) {
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", false).put("error", "media_url_rejected_https_required").toString())
                        } else {
                            runOnUiThread { openNativeMedia(mediaUrl?.toASCIIString()) }
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                        }
                    }
                    "open_agent" -> {
                        runOnUiThread { startActivity(Intent(this@MainActivity, SpiderAgentActivity::class.java)) }
                        replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                    }
                    "open_llm" -> {
                        runOnUiThread { startActivity(Intent(this@MainActivity, SpiderLlmActivity::class.java)) }
                        replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                    }
                    "enable_overlay" -> {
                        runOnUiThread { requestOverlayPermissionOrStart() }
                        replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                    }
                    "disable_overlay" -> {
                        runOnUiThread {
                            startService(Intent(this@MainActivity, SpiderOverlayService::class.java).setAction(SpiderOverlayService.ACTION_STOP))
                            Toast.makeText(this@MainActivity, "Плавающий Паук скрыт", Toast.LENGTH_SHORT).show()
                        }
                        replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                    }
                    "save_html" -> {
                        val html = payload.optString("html", "")
                        val safeName = payload.optString("filename", "spider-demo.html")
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).let { if (it.endsWith(".html", true)) it else "$it.html" }
                        if (html.isBlank() || html.length > MAX_HTML_PAYLOAD) {
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", false).put("error", "html_payload_invalid").toString())
                        } else {
                            runOnUiThread { launchHtmlSavePicker(html, safeName) }
                            replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", true).put("accepted", true).toString())
                        }
                    }
                    else -> replyProxy.postMessage(JSONObject().put("id", requestId).put("ok", false).put("error", "method_not_allowed").toString())
                }
            }
            bridgeReady = true
        } catch (_: Exception) {
            bridgeReady = false // Fail closed on runtime incompatibility.
        }

    }

    private fun installMinimalFacade() {
        val script = """
            (function(){
              if (!window.spiderNative || typeof window.spiderNative.postMessage !== 'function') return;
              if (window.__iiPaukNativeFacade) return;
              window.__iiPaukNativeFacade = true;
              const post = (method, url) => {
                try {
                  const message = {id:'spider-'+Date.now()+'-'+Math.random().toString(36).slice(2,8), method:method};
                  if (url) message.url = url;
                  else if (method === 'open_browser') message.url = '$DEFAULT_BROWSER_URL';
                  window.spiderNative.postMessage(JSON.stringify(message));
                  return true;
                } catch (_) { return false; }
              };
              const saveHtml = (html, filename) => {
                try {
                  if (String(html||'').length > 180000) return false;
                  window.spiderNative.postMessage(JSON.stringify({id:'spider-save-'+Date.now(),method:'save_html',html:String(html||''),filename:String(filename||'spider-demo.html')}));
                  return true;
                } catch (_) { return false; }
              };
              window.Android = Object.freeze({
                openSpiderBrowser: function(url){ return post('open_browser', url); },
                openSpiderTerminal: function(){ return post('open_terminal'); },
                openSpiderMedia: function(url){ return post('open_media', url); },
                openSpiderAgent: function(){ return post('open_agent'); },
                openSpiderLlm: function(){ return post('open_llm'); },
                enableSpiderOverlay: function(){ return post('enable_overlay'); },
                disableSpiderOverlay: function(){ return post('disable_overlay'); },
                saveSpiderHtml: function(html, filename){ return saveHtml(html, filename); },
                getNativeCapabilities: function(){ return {
                  appId:'com.iipauk.spider', appVersion:'0.39.3', platform:'android',
                  androidVersion:'${Build.VERSION.RELEASE}', sdkInt:${Build.VERSION.SDK_INT},
                  supportedAbis:${JSONObject.quote(Build.SUPPORTED_ABIS.joinToString(","))},
                  webViewMessageListener:true, browserActivity:true, mediaActivitySource:true, mediaSessionServiceSource:true, agentTaskManagerSource:true, agentPlannerSource:true, agentPlannerConfigured:${agentRuntime.isProviderConfigured()},
                  mediaPlayerSourceAvailable:true, mediaNetworkStreamingSourceAvailable:true, mediaHlsSourceAvailable:true, mediaDashSourceAvailable:true, mediaPlayerVerified:false, backgroundPlaybackVerified:false,
                  overlaySourceAvailable:true, overlayPermissionGranted:${Settings.canDrawOverlays(this)}, overlayEdgeRetractionSourceAvailable:true, overlayTouchToPeekSourceAvailable:true, htmlExportSourceAvailable:true, htmlDemoPreviewAvailable:true, apkBuildRunnerAvailable:false,
                  terminal:true, terminalPty:true, nativeTerminal:true, terminalPtyVerified:false, linuxRuntime:false,
                  openCodeZenApiSourceAvailable:true, openCodeZenApiConfigured:${runCatching { SpiderOpenCodeZenConfigStore(this).isConfigured() }.getOrDefault(false)}, openCodeZenApiVerified:false,
                  localLlmEngineAvailable:${agentRuntime.localLlmRuntime.engineAvailable}, localBrain:${agentRuntime.localLlmRuntime.isModelLoaded}, llmMode:'${agentRuntime.llmMode().name}',
                  agentPolicyCoreSource:true, agentBrokerExecutable:false,
                  runtimeStatus:'browser-source-ready; media-local-and-https-streaming-source-added-unverified; media-hls-dash-source-added-unverified; overlay-edge-retraction-and-peek-source-added-device-check-pending; html-code-studio-source-added; opencode-zen-direct-api-source-added-live-key-check-pending; agent-planner-source-added; agent-task-ui-source-added; five-workspace-tool-handlers-registered-source-checks-passed-device-check-pending; local-llm-source-added-unverified; yandex-backend-adapter-added-live-connection-unverified; apk-build-runner-pending; android-system-pty-integrated-unverified; linux-not-installed'
                }; }
              });
              // Browser entry points call Android.openSpiderBrowser() from the app's own UI handler.
              // Do not intercept clicks here: app-local tab state is scoped inside its IIFE.

            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun openNativeBrowser(uri: Uri) {
        startActivity(Intent(this, SpiderBrowserActivity::class.java).putExtra(EXTRA_BROWSER_URL, uri.toString()))
    }

    private fun openNativeMedia(url: String? = null) {
        val intent = Intent(this, SpiderMediaActivity::class.java)
        if (!url.isNullOrBlank()) intent.putExtra(SpiderMediaActivity.EXTRA_MEDIA_URL, url)
        startActivity(intent)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = null
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BROWSER_URL = "com.iipauk.spider.EXTRA_BROWSER_URL"
        const val EXTRA_OVERLAY_SECTION = "com.iipauk.spider.EXTRA_OVERLAY_SECTION"
        private const val REQUEST_OVERLAY_PERMISSION = 7301
        private const val REQUEST_SAVE_HTML = 7302
        private const val MAX_HTML_PAYLOAD = 180_000
        private const val APP_URL = "https://appassets.androidplatform.net/assets/web/ii-pauk.html"
        private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val BRIDGE_OBJECT = "spiderNative"
        private const val DEFAULT_BROWSER_URL = "https://duckduckgo.com/"
        private const val MAX_BRIDGE_MESSAGE = 400_000
    }

    override fun onResume() {
        super.onResume()
        if (overlayPermissionRequestPending && Settings.canDrawOverlays(this)) {
            overlayPermissionRequestPending = false
            startOverlayService()
            notifyOverlayPermission(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOverlaySection = intent.getStringExtra(EXTRA_OVERLAY_SECTION)
        if (::webView.isInitialized && webView.url == APP_URL) applyPendingOverlaySection()
    }

    private fun applyPendingOverlaySection() {
        val section = pendingOverlaySection ?: return
        pendingOverlaySection = null
        val safe = section.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
        webView.post {
            webView.evaluateJavascript("(function(){var s=${JSONObject.quote(safe)}; if(s==='home'){return;} var b=document.querySelector('[data-page=\"'+s+'\"]'); if(b){b.click();return;} if(s==='chat'){var p=document.querySelector('#prompt');if(p)p.focus();}})();", null)
        }
    }

    private fun requestOverlayPermissionOrStart() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
            notifyOverlayPermission(true)
            return
        }
        overlayPermissionRequestPending = true
        val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivityForResult(permissionIntent, REQUEST_OVERLAY_PERMISSION)
        } catch (_: ActivityNotFoundException) {
            overlayPermissionRequestPending = false
            Toast.makeText(this, "Открой настройки Android и разреши показ поверх других приложений для ИИ Паука", Toast.LENGTH_LONG).show()
            notifyOverlayPermission(false)
        }
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) return
        val serviceIntent = Intent(this, SpiderOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
            Toast.makeText(this, "Плавающий Паук включён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось включить виджет: ${e.message ?: "ошибка запуска"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyOverlayPermission(granted: Boolean) {
        if (!::webView.isInitialized) return
        val payload = JSONObject().put("granted", granted).toString()
        webView.post { webView.evaluateJavascript("window.onSpiderOverlayPermissionResult && window.onSpiderOverlayPermissionResult($payload)", null) }
    }

    private fun launchHtmlSavePicker(html: String, filename: String) {
        pendingHtml = html
        pendingHtmlFileName = filename
        val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        try {
            startActivityForResult(saveIntent, REQUEST_SAVE_HTML)
        } catch (_: Exception) {
            pendingHtml = null
            notifyHtmlSaveResult(false, "Не удалось открыть выбор места сохранения")
        }
    }

    @Deprecated("Deprecated by Android; retained for broad API compatibility in this scaffold")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                overlayPermissionRequestPending = false
                val granted = Settings.canDrawOverlays(this)
                if (granted) startOverlayService()
                else Toast.makeText(this, "Без разрешения Android плавающий виджет не может показываться поверх других приложений", Toast.LENGTH_LONG).show()
                notifyOverlayPermission(granted)
            }
            REQUEST_SAVE_HTML -> {
                val html = pendingHtml
                pendingHtml = null
                if (resultCode == RESULT_OK && data?.data != null && html != null) {
                    val result = runCatching {
                        contentResolver.openOutputStream(data.data!!, "w")!!.use { it.write(html.toByteArray(Charsets.UTF_8)) }
                    }
                    if (result.isSuccess) notifyHtmlSaveResult(true, "HTML сохранён: $pendingHtmlFileName")
                    else notifyHtmlSaveResult(false, "Не удалось сохранить HTML: ${result.exceptionOrNull()?.message ?: "ошибка записи"}")
                } else {
                    notifyHtmlSaveResult(false, "Сохранение HTML отменено")
                }
            }
        }
    }

    private fun notifyHtmlSaveResult(ok: Boolean, message: String) {
        if (!::webView.isInitialized) return
        val payload = JSONObject().put("ok", ok).put("message", message.take(200)).toString()
        webView.post { webView.evaluateJavascript("window.onSpiderNativeSaveResult && window.onSpiderNativeSaveResult($payload)", null) }
    }
}
