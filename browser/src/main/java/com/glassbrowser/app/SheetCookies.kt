package com.glassbrowser.app

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.CookieManager

/**
 * Лист Cookies: форма добавления, список cookies сайта (из WebView),
 * наше хранилище, журнал применения (4 способа).
 */
class SheetCookies(
    activity: MainActivity,
    private val onApplied: () -> Unit
) : BaseSheet(activity, R.layout.sheet_cookies) {

    private var inputName: EditText? = null
    private var inputValue: EditText? = null
    private var inputDomain: EditText? = null
    private var chkHttpOnly: CheckBox? = null
    private var chkSecure: CheckBox? = null
    private var hostView: TextView? = null
    private var siteList: LinearLayout? = null
    private var ourList: LinearLayout? = null
    private var logView: TextView? = null

    override fun setup(view: View) {
        inputName = view.findViewById(R.id.inputCookieName)
        inputValue = view.findViewById(R.id.inputCookieValue)
        inputDomain = view.findViewById(R.id.inputCookieDomain)
        chkHttpOnly = view.findViewById(R.id.chkHttpOnly)
        chkSecure = view.findViewById(R.id.chkSecure)
        hostView = view.findViewById(R.id.cookiesHost)
        siteList = view.findViewById(R.id.cookieList)
        ourList = view.findViewById(R.id.ourCookieList)
        logView = view.findViewById(R.id.cookieLog)

        CookieStore.load(activity)

        val host = activity.currentHost()
        hostView?.text = getString(R.string.cookies_host, host.ifBlank { "(нет страницы)" })
        inputDomain?.setText(host)

        view.findViewById<Button>(R.id.btnCookieAddApply).setOnClickListener { addAndApply() }
        view.findViewById<Button>(R.id.btnSheetClose).setOnClickListener { close() }

        rebuildSiteList()
        rebuildOurList()
    }

    private fun getString(res: Int): String = activity.getString(res)

    private fun getString(res: Int, arg: String): String =
        activity.getString(res, arg)

    private fun addAndApply() {
        val name = inputName?.text?.toString()?.trim() ?: ""
        val value = inputValue?.text?.toString() ?: ""
        val domain = inputDomain?.text?.toString()?.trim() ?: activity.currentHost()
        if (name.isEmpty()) {
            toast(getString(R.string.cookie_empty_name))
            return
        }
        if (value.isEmpty()) {
            toast(getString(R.string.cookie_empty_value))
            return
        }
        val c = CookieStore.GhCookie(
            name = name,
            value = value,
            domain = domain,
            httponly = chkHttpOnly?.isChecked == true,
            secure = chkSecure?.isChecked == true
        )
        CookieStore.addOrUpdate(c)
        CookieStore.save(activity)
        val lines = CookieStore.applyAll(activity, activity.webView, activity.currentHost(), activity.currentUrl())
        appendLog(lines)
        inputName?.text?.clear()
        inputValue?.text?.clear()
        rebuildOurList()
        rebuildSiteList()
        onApplied()
        toast("Cookie добавлен и применён")
    }

    private fun appendLog(lines: List<String>) {
        val cur = logView?.text?.toString() ?: ""
        val add = lines.joinToString("\n")
        logView?.text = if (cur.isBlank()) add else cur + "\n" + add
    }

    /** Cookies текущего сайта из CookieManager + удаление по одному. */
    private fun rebuildSiteList() {
        val list = siteList ?: return
        list.removeAllViews()
        val url = activity.currentUrl()
        if (url.isBlank()) return
        val raw = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
        if (raw.isNullOrBlank()) {
            list.addView(simpleRow("На сайте cookies нет"))
            return
        }
        for (pair in raw.split("; ")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val nm = if (eq >= 0) pair.substring(0, eq) else pair
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(8, 6, 8, 6)

            val tv = TextView(activity)
            tv.text = pair
            tv.textSize = 12f
            tv.setTextColor(activity.colorOf(R.color.ink))
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)

            val del = ImageButton(activity)
            del.setImageResource(R.drawable.ic_trash)
            del.background = null
            del.setColorFilter(activity.colorOf(R.color.danger))
            val pad = (activity.resources.displayMetrics.density * 10).toInt()
            del.setPadding(pad, pad, pad, pad)
            del.contentDescription = activity.getString(R.string.delete)
            del.setOnClickListener {
                CookieStore.expireOnSite(activity, url, nm)
                appendLog(listOf("Удалён с сайта: $nm"))
                rebuildSiteList()
                onApplied()
            }
            row.addView(del)
            list.addView(row)
        }
    }

    /** Наше хранилище: строка + удаление. */
    private fun rebuildOurList() {
        val list = ourList ?: return
        list.removeAllViews()
        CookieStore.load(activity)
        if (CookieStore.items.isEmpty()) {
            list.addView(simpleRow("Пока пусто — добавьте cookie сверху"))
            return
        }
        for (i in CookieStore.items.indices) {
            val c = CookieStore.items[i]
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(8, 6, 8, 6)

            val tv = TextView(activity)
            val flags = buildString {
                if (c.httponly) append(" [HttpOnly]")
                if (c.secure) append(" [Secure]")
            }
            tv.text = "${c.name}=${c.value} → ${c.domain}$flags"
            tv.textSize = 12f
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setTextColor(activity.colorOf(R.color.ink))
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)

            val del = ImageButton(activity)
            del.setImageResource(R.drawable.ic_trash)
            del.background = null
            del.setColorFilter(activity.colorOf(R.color.danger))
            val pad = (activity.resources.displayMetrics.density * 10).toInt()
            del.setPadding(pad, pad, pad, pad)
            del.contentDescription = activity.getString(R.string.delete)
            del.setOnClickListener {
                CookieStore.removeAt(i)
                CookieStore.save(activity)
                rebuildOurList()
                appendLog(listOf("Удалено из хранилища: ${c.name}"))
            }
            row.addView(del)
            list.addView(row)
        }
    }

    private fun simpleRow(text: String): TextView {
        val tv = TextView(activity)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(activity.colorOf(R.color.ink2))
        tv.setPadding(8, 8, 8, 8)
        return tv
    }

    override fun onDismiss() {
        inputName = null
        inputValue = null
        inputDomain = null
        chkHttpOnly = null
        chkSecure = null
        hostView = null
        siteList = null
        ourList = null
        logView = null
    }
}
