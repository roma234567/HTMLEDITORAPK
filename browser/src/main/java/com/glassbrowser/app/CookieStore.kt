package com.glassbrowser.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище пользовательских cookies + четыре способа их применения:
 *  1) CookieManager.setCookie + flush — основной путь WebView;
 *  2) document.cookie через evaluateJavascript — «живое» добавление в DOM страницы;
 *  3) заголовок Cookie к loadUrl — форс-перезагрузка с cookies;
 *  4) заголовок Cookie, вставляемый LAN-прокси в обычные HTTP-запросы.
 */
object CookieStore {

    const val PREFS = "glass_cookies"

    data class GhCookie(
        val name: String,
        val value: String,
        val domain: String,
        val httponly: Boolean,
        val secure: Boolean,
        val path: String = "/"
    ) {
        fun cookieString(): String {
            val sb = StringBuilder()
            sb.append(name).append('=').append(value)
            sb.append("; path=").append(path)
            if (domain.isNotBlank()) sb.append("; domain=").append(domain)
            if (httponly) sb.append("; HttpOnly")
            if (secure) sb.append("; Secure")
            sb.append("; Max-Age=31536000")
            return sb.toString()
        }

        fun expiredString(): String = "$name=; path=$path; Max-Age=0"
    }

    val items = ArrayList<GhCookie>()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        items.clear()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("list", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    GhCookie(
                        name = o.optString("name"),
                        value = o.optString("value"),
                        domain = o.optString("domain"),
                        httponly = o.optBoolean("httponly"),
                        secure = o.optBoolean("secure"),
                        path = o.optString("path", "/")
                    )
                )
            }
        } catch (e: Exception) {
            // повреждённое хранилище — начинаем с пустого
        }
    }

    fun save(context: Context) {
        val arr = JSONArray()
        for (c in items) {
            val o = JSONObject()
            o.put("name", c.name)
            o.put("value", c.value)
            o.put("domain", c.domain)
            o.put("httponly", c.httponly)
            o.put("secure", c.secure)
            o.put("path", c.path)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("list", arr.toString()).apply()
    }

    fun addOrUpdate(c: GhCookie) {
        items.removeAll { it.name == c.name && it.domain == c.domain }
        items.add(c)
    }

    fun removeAt(index: Int) {
        if (index in items.indices) items.removeAt(index)
    }

    fun matching(host: String): List<GhCookie> {
        if (host.isBlank()) return emptyList()
        val h = host.lowercase()
        return items.filter { c ->
            val d = c.domain.trim().lowercase().trimStart('.')
            d.isBlank() || h == d || h.endsWith(".$d")
        }
    }

    /** Строка заголовка Cookie для конкретного хоста (способ 3 и 4). */
    fun headerFor(host: String): String {
        val list = matching(host)
        if (list.isEmpty()) return ""
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Применяет все cookies по способам 1, 2 и 4.
     * Возвращает список записей журнала.
     */
    fun applyAll(
        activity: MainActivity,
        webView: WebView,
        host: String,
        url: String
    ): List<String> {
        load(activity)
        val log = ArrayList<String>()
        if (items.isEmpty()) {
            log.add("Список cookies пуст")
            return log
        }
        val cm = CookieManager.getInstance()

        // --- способ 1: CookieManager + flush ---
        var n1 = 0
        for (c in items) {
            if (c.secure && url.startsWith("http://")) {
                log.add("СКИП secure- cookie (страница не https): ${c.name}")
                continue
            }
            try {
                cm.setCookie(url, c.cookieString())
                n1++
            } catch (e: Exception) {
                log.add("Ошибка setCookie ${c.name}: ${e.message}")
            }
        }
        try {
            cm.flush()
        } catch (e: Exception) {
            log.add("Ошибка flush: ${e.message}")
        }
        log.add("1/4 CookieManager: записано $n1, flush")

        // --- способ 2: document.cookie (не для HttpOnly) ---
        val domList = items.filter { !it.httponly && (!it.secure || url.startsWith("https://")) }
        if (domList.isEmpty()) {
            log.add("2/4 document.cookie: некого добавлять (все HttpOnly/secure)")
        } else {
            for (c in domList) {
                val js2 = "document.cookie = " + jsStr(
                    c.name + "=" + c.value + "; path=" + c.path +
                        (if (c.domain.isNotBlank()) "; domain=" + c.domain else "")
                ) + "; void 0;"
                activity.runJs(js2) { }
            }
            log.add("2/4 document.cookie: ${domList.size} шт (eval)")
        }

        // --- способ 4: заголовок для LAN-прокси ---
        val hdr = headerFor(host)
        if (hdr.isNotEmpty()) {
            ProxyServer.setHeaderCookie(hdr)
            log.add("4/4 LAN-прокси: заголовок Cookie обновлён (${hdr.length} симв.)")
        } else {
            log.add("4/4 LAN-прокси: хосту $host cookies не подходят")
        }

        // --- способ 3: заголовок Cookie при перезагрузке ---
        if (hdr.isNotEmpty()) {
            log.add("3/4 заголовок Cookie: перезагрузка страницы")
            activity.reloadWithCookies()
        } else {
            log.add("3/4 заголовок Cookie: пропущен (нет совпадений)")
        }
        return log
    }

    /** Удаляет cookie из WebView (просроченный) и из хранилища. */
    fun expireOnSite(activity: MainActivity, url: String, name: String) {
        try {
            CookieManager.getInstance().setCookie(url, "$name=; path=/; Max-Age=0")
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun jsStr(s: String): String {
        val sb = StringBuilder("'")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u0000' -> { /* пропуск */ }
                else -> sb.append(ch)
            }
        }
        sb.append('\'')
        return sb.toString()
    }
}
