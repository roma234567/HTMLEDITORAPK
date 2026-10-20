package com.glassbrowser.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URLEncoder

/**
 * Glass Browser: просмотр сайтов (URL), стеклянный UI, режим ПК/телефон,
 * темы, cookies (4 способа), инспектор элементов, JS/SQL, прокси.
 */
class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
        private set

    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnMenu: ImageButton

    private var phoneUserAgent: String = ""

    // листы
    private val sheetMenu by lazy {
        SheetMenu(
            this,
            onCookies = { cookiesSheet().show() },
            onInspector = { inspectorSheet().show() },
            onJs = { jsSheet().show() },
            onSql = { sqlSheet().show() },
            onProxy = { proxySheet().show() },
            onThemeToggle = { toggleTheme() }
        )
    }
    private var cookiesSheetInst: SheetCookies? = null
    private var inspectorSheetInst: SheetInspector? = null
    private var jsSheetInst: SheetJs? = null
    private var sqlSheetInst: SheetSql? = null
    private var proxySheetInst: SheetProxy? = null

    private fun cookiesSheet(): SheetCookies =
        cookiesSheetInst ?: SheetCookies(this) { }.also { cookiesSheetInst = it }

    private fun inspectorSheet(): SheetInspector =
        inspectorSheetInst ?: SheetInspector(this).also { inspectorSheetInst = it }

    private fun jsSheet(): SheetJs =
        jsSheetInst ?: SheetJs(this).also { jsSheetInst = it }

    private fun sqlSheet(): SheetSql =
        sqlSheetInst ?: SheetSql(this).also { sqlSheetInst = it }

    private fun proxySheet(): SheetProxy =
        proxySheetInst ?: SheetProxy(this).also { proxySheetInst = it }

    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        PrefsX.init(this)
        AppCompatDelegate.setDefaultNightMode(
            if (PrefsX.night) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        CookieStore.load(this)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnReload = findViewById(R.id.btnReload)
        btnMode = findViewById(R.id.btnMode)
        btnMenu = findViewById(R.id.btnMenu)

        setupEdgeToEdge()
        setupWebView()
        setupChrome()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        }
        val savedUrl = savedInstanceState?.getString("url")
            ?: PrefsX.lastUrl
        if (savedUrl.isNotBlank() && webView.url == null) {
            webView.loadUrl(savedUrl)
        }

        // автозапуск прокси, если был включён
        if (PrefsX.proxyAutoStart) {
            ProxyServer.start()
        }

        // синхронизация адреса после восстановления
        urlInput.post { syncUrlFromWebView(force = true) }
    }

    // ------------------------------------------------------------------
    // Настройка
    // ------------------------------------------------------------------

    private fun setupEdgeToEdge() {
        val root = findViewById<View>(R.id.root)
        val chromeTop = findViewById<View>(R.id.chromeTop)
        val chromeBottom = findViewById<View>(R.id.chromeBottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            root.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val baseTopTop = chromeTop.paddingTop
        val baseTopBottom = chromeTop.paddingBottom
        val baseBottomTop = chromeBottom.paddingTop
        val baseBottomBottom = chromeBottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            chromeTop.setPadding(
                chromeTop.paddingLeft, baseTopTop + bars.top,
                chromeTop.paddingRight, baseTopBottom
            )
            chromeBottom.setPadding(
                chromeBottom.paddingLeft, baseBottomTop,
                chromeBottom.paddingRight, baseBottomBottom + bars.bottom
            )
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        phoneUserAgent = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        applyUserAgent(reload = false)
        applyViewPortMode()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "data" ||
                    scheme == "about" || scheme == "blob" || scheme == "file"
                ) {
                    return false // грузим в WebView
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
                syncUrlFromWebView()
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                syncUrlFromWebView(force = true)
                updateNavButtons()
                runAutoSnippets()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString() ?: ""
                    if (desc.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, desc, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceRequest? {
                if (PrefsX.interceptOn && request != null) {
                    val u = request.url
                    val scheme = u.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        val line = "${request.method} $u"
                        var cookieEcho = ""
                        try {
                            val ck = CookieManager.getInstance().getCookie(u.toString())
                            if (!ck.isNullOrBlank()) cookieEcho = ck.take(160)
                        } catch (e: Exception) {
                            // ignore
                        }
                        LogHub.log(
                            LogHub.WEB,
                            line + if (cookieEcho.isNotEmpty()) " | Cookie: $cookieEcho" else ""
                        )
                    }
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (PrefsX.interceptOn && consoleMessage != null) {
                    LogHub.log(
                        LogHub.WEB,
                        "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                resultMsg: JsPromptResult?
            ): Boolean {
                if (message != null && resultMsg != null &&
                    InspectorBridge.handlePrompt(message, url)
                ) {
                    resultMsg.confirm("")
                    return true
                }
                return super.onJsPrompt(view, url, message, resultMsg)
            }
        }
    }

    private fun setupChrome() {
        // omnibox
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(urlInput.text.toString())
                true
            } else false
        }
        findViewById<ImageButton>(R.id.btnGo)?.setOnClickListener {
            navigate(urlInput.text.toString())
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnReload.setOnClickListener { webView.reload() }
        btnMode.setOnClickListener { toggleViewMode() }
        btnMenu.setOnClickListener { sheetMenu.show() }

        applyModeIcon()
        updateNavButtons()
    }

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    private fun navigate(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") ||
                t.startsWith("about:") || t.startsWith("data:") ||
                t.startsWith("file:") || t.startsWith("javascript:") -> t
            t.startsWith("//") -> "https:$t"
            t.contains(" ") || !t.contains(".") ->
                "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
        hideKeyboard()
        webView.loadUrl(url)
        urlInput.clearFocus()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun syncUrlFromWebView(force: Boolean = false) {
        val u = webView.url ?: return
        if (!force && urlInput.hasFocus()) return
        if (urlInput.text.toString() == u) return
        urlInput.setText(u)
        PrefsX.lastUrl = u
    }

    private fun updateNavButtons() {
        val backOn = webView.canGoBack()
        val fwdOn = webView.canGoForward()
        btnBack.alpha = if (backOn) 1f else 0.35f
        btnForward.alpha = if (fwdOn) 1f else 0.35f
        btnBack.isEnabled = true // нажатие просто не сработает
        btnForward.isEnabled = true
    }

    /** Доступно из листов. */
    fun currentUrl(): String = webView.url ?: ""

    fun currentHost(): String {
        val u = webView.url ?: return ""
        return try {
            Uri.parse(u).host ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Способ 3: загрузка с заголовком Cookie. */
    fun reloadWithCookies() {
        val u = webView.url ?: return
        val host = currentHost()
        val cookie = CookieStore.headerFor(host)
        if (cookie.isEmpty()) return
        val headers = HashMap<String, String>()
        headers["Cookie"] = cookie
        webView.loadUrl(u, headers)
    }

    /** Выполнить JS на странице (callback в UI-потоке). */
    fun runJs(js: String, cb: ((String) -> Unit)? = null) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(js) { v ->
                    runOnUiThread { cb?.invoke(v ?: "null") }
                }
            } catch (e: Exception) {
                runOnUiThread { cb?.invoke("ERR: " + (e.message ?: "?")) }
            }
        }
    }

    private fun runAutoSnippets() {
        for (o in PrefsX.snippets()) {
            if (o.optBoolean("auto", false)) {
                val code = o.optString("code", "")
                if (code.isNotBlank()) {
                    runJs(InspectorBridge.runUserCode(code)) { }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Режим ПК/телефон
    // ------------------------------------------------------------------

    private fun toggleViewMode() {
        PrefsX.desktopMode = !PrefsX.desktopMode
        applyUserAgent(reload = false)
        applyViewPortMode()
        applyModeIcon()
        webView.reload()
        Toast.makeText(
            this,
            getString(if (PrefsX.desktopMode) R.string.toast_mode_desktop else R.string.toast_mode_phone),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyUserAgent(reload: Boolean) {
        webView.settings.userAgentString =
            if (PrefsX.desktopMode) DESKTOP_UA else phoneUserAgent
        if (reload) webView.reload()
    }

    private fun applyViewPortMode() {
        // desktop: уважаем viewport сайта без масштабирования-обзора
        webView.settings.loadWithOverviewMode = !PrefsX.desktopMode
        webView.settings.useWideViewPort = true
    }

    private fun applyModeIcon() {
        if (PrefsX.desktopMode) {
            btnMode.setImageResource(R.drawable.ic_desktop)
            btnMode.contentDescription = getString(R.string.mode_desktop)
        } else {
            btnMode.setImageResource(R.drawable.ic_phone)
            btnMode.contentDescription = getString(R.string.mode_phone)
        }
    }

    // ------------------------------------------------------------------
    // Тема
    // ------------------------------------------------------------------

    private fun toggleTheme() {
        PrefsX.night = !PrefsX.night
        AppCompatDelegate.setDefaultNightMode(
            if (PrefsX.night) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        // saveState() сохранит WebView и URL — после recreate восстановим
    }

    // ------------------------------------------------------------------
    // Цвета ресурсов для кода (листы)
    // ------------------------------------------------------------------

    fun colorOf(res: Int): Int = getColor(res)

    fun drawable(res: Int): android.graphics.drawable.Drawable =
        getDrawable(res)!!

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { webView.saveState(outState) } catch (e: Exception) { /* ignore */ }
        outState.putString("url", webView.url ?: PrefsX.lastUrl)
    }

    override fun onPause() {
        super.onPause()
        try { webView.onPause() } catch (e: Exception) { /* ignore */ }
    }

    override fun onResume() {
        super.onResume()
        try { webView.onResume() } catch (e: Exception) { /* ignore */ }
    }

    override fun onDestroy() {
        try {
            ProxyServer.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}
