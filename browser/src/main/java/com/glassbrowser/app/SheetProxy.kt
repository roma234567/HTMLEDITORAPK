package com.glassbrowser.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat

/**
 * Лист прокси: запуск LAN-прокси, адрес для внешних клиентов,
 * тумблер перехвата WebView и оба журнала (LAN + WebView).
 */
class SheetProxy(activity: MainActivity) : BaseSheet(activity, R.layout.sheet_proxy) {

    private var addrView: TextView? = null
    private var toggleBtn: Button? = null
    private var chkIntercept: SwitchCompat? = null
    private var lanLog: TextView? = null
    private var webLog: TextView? = null
    private var lanScroll: ScrollView? = null
    private var webScroll: ScrollView? = null

    private val listener: (String) -> Unit = { line ->
        post { appendLine(line) }
    }

    override fun setup(view: View) {
        addrView = view.findViewById(R.id.proxyAddr)
        toggleBtn = view.findViewById(R.id.btnProxyToggle)
        chkIntercept = view.findViewById(R.id.chkIntercept)
        lanLog = view.findViewById(R.id.proxyLog)
        webLog = view.findViewById(R.id.internalLog)
        lanScroll = view.findViewById(R.id.proxyLogScroll)
        webScroll = view.findViewById(R.id.internalLogScroll)

        refreshAddr()
        toggleBtn?.text = getString(
            if (ProxyServer.running) R.string.proxy_toggle_off else R.string.proxy_toggle_on
        )
        chkIntercept?.isChecked = PrefsX.interceptOn
        chkIntercept?.setOnCheckedChangeListener { _, checked ->
            PrefsX.interceptOn = checked
            LogHub.log(
                LogHub.WEB,
                if (checked) "перехват запросов ВКЛ" else "перехват запросов ВЫКЛ"
            )
            refreshLogs()
        }

        toggleBtn?.setOnClickListener { toggleProxy() }
        view.findViewById<Button>(R.id.btnProxyCopy).setOnClickListener { copyAddr() }
        view.findViewById<Button>(R.id.btnSheetClose).setOnClickListener { close() }

        refreshLogs()
        LogHub.addListener(listener)
    }

    override fun onDismiss() {
        LogHub.removeListener(listener)
        addrView = null
        toggleBtn = null
        chkIntercept = null
        lanLog = null
        webLog = null
        lanScroll = null
        webScroll = null
    }

    private fun toggleProxy() {
        if (ProxyServer.running) {
            ProxyServer.stop()
            PrefsX.proxyAutoStart = false
        } else {
            val err = ProxyServer.start()
            if (err != null) {
                toast("Не запустился: $err")
            } else {
                PrefsX.proxyAutoStart = true
            }
        }
        refreshAddr()
        toggleBtn?.text = getString(
            if (ProxyServer.running) R.string.proxy_toggle_off else R.string.proxy_toggle_on
        )
        refreshLogs()
    }

    private fun refreshAddr() {
        val a = ProxyServer.addressString()
        addrView?.text = if (a.isEmpty()) "— прокси выключен —" else getString(R.string.proxy_addr, a)
    }

    private fun copyAddr() {
        val a = ProxyServer.addressString()
        if (a.isEmpty()) {
            toast("Прокси выключен")
            return
        }
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("proxy", a))
            Toast.makeText(activity, getString(R.string.proxy_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun refreshLogs() {
        lanLog?.text = LogHub.dump(LogHub.LAN).ifBlank { "(пусто)" }
        webLog?.text = LogHub.dump(LogHub.WEB).ifBlank { "(пусто)" }
        lanScroll?.post { lanScroll?.fullScroll(View.FOCUS_DOWN) }
        webScroll?.post { webScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLine(line: String) {
        val i = line.indexOf("] ")
        val rest = if (i >= 0) line.substring(i + 2) else line
        val isLan = line.contains("[${LogHub.LAN}]")
        val tv = if (isLan) lanLog else webLog
        val scroll = if (isLan) lanScroll else webScroll
        val cur = tv?.text?.toString() ?: ""
        tv?.text = if (cur.isBlank() || cur == "(пусто)") rest else cur + "\n" + rest
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun getString(res: Int): String = activity.getString(res)
    private fun getString(res: Int, arg: String): String = activity.getString(res, arg)
}
