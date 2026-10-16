package com.htmleditor.app

import android.content.Context
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Управление предпросмотром: режим «Телефон»/«ПК», размер страницы (зум),
 * загрузка текущего кода в WebView.
 */
class PreviewController(private val webView: WebView) {

    var mode: String = Prefs.MODE_PHONE
    var zoom: Int = 100

    private var phoneUa: String? = null

    fun setup(context: Context) {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.textZoom = zoom
        webView.setBackgroundColor(Color.WHITE)
        phoneUa = WebSettings.getDefaultUserAgent(context)
    }

    fun applyZoom() {
        webView.settings.textZoom = zoom
    }

    fun load(html: String) {
        val context = webView.context
        val s = webView.settings
        val pc = mode == Prefs.MODE_PC

        s.userAgentString = if (pc) {
            PC_USER_AGENT
        } else {
            phoneUa ?: WebSettings.getDefaultUserAgent(context).also { phoneUa = it }
        }
        s.setUseWideViewPort(true)
        s.loadWithOverviewMode = true
        s.textZoom = zoom

        webView.loadDataWithBaseURL(null, applyViewport(html, pc), "text/html", "utf-8", null)
    }

    /**
     * Для режима «ПК» страница получает широкий вьюпорт (1280),
     * для «Телефона» — стандартный адаптивный вьюпорт.
     */
    private fun applyViewport(html: String, pc: Boolean): String {
        val meta = if (pc) {
            """<meta name="viewport" content="width=1280">"""
        } else {
            """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
        }
        if (VIEWPORT.containsMatchIn(html)) {
            return VIEWPORT.replace(html, meta)
        }
        val head = HEAD.find(html)
        return if (head != null) {
            val insertAt = head.range.last + 1
            html.substring(0, insertAt) + "\n    " + meta + html.substring(insertAt)
        } else {
            meta + "\n" + html
        }
    }

    companion object {
        private const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val VIEWPORT = Regex(
            "(?is)<meta\\s[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*>"
        )
        private val HEAD = Regex("(?i)<head[^>]*>")
    }
}
