package com.glassbrowser.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * DevTools-подобный инспектор: выбор элемента на странице,
 * редактирование outerHTML и inline-стилей, применение обратно.
 */
class SheetInspector(activity: MainActivity) : BaseSheet(activity, R.layout.sheet_inspector) {

    private var target: TextView? = null
    private var code: EditText? = null
    private var btnHtml: Button? = null
    private var btnCss: Button? = null

    /** CSS-селектор выбранного элемента. */
    private var cssPath: String = ""
    private var mode: String = MODE_HTML

    override fun setup(view: View) {
        target = view.findViewById(R.id.inspTarget)
        code = view.findViewById(R.id.inspCode)
        btnHtml = view.findViewById(R.id.btnInspHtml)
        btnCss = view.findViewById(R.id.btnInspCss)

        btnHtml?.setOnClickListener { switchMode(MODE_HTML) }
        btnCss?.setOnClickListener { switchMode(MODE_CSS) }

        view.findViewById<Button>(R.id.btnInspApply).setOnClickListener { applyEdits() }
        view.findViewById<Button>(R.id.btnInspRepick).setOnClickListener { startPick() }
        view.findViewById<Button>(R.id.btnInspCopy).setOnClickListener { copyCode() }
        view.findViewById<Button>(R.id.btnInspPick).setOnClickListener { startPick() }
        view.findViewById<Button>(R.id.btnInspStop).setOnClickListener { stopPick() }
        view.findViewById<Button>(R.id.btnSheetClose).setOnClickListener { close() }

        switchMode(MODE_HTML)
        startPick()
    }

    private fun switchMode(m: String) {
        mode = m
        val on = activity.colorOf(R.color.accent)
        val offBg = activity.drawable(R.drawable.bg_seg_off)
        val onBg = activity.drawable(R.drawable.bg_seg_on)
        val ink = activity.colorOf(R.color.ink)
        val onText = activity.colorOf(R.color.accent_on)
        if (m == MODE_HTML) {
            btnHtml?.background = onBg
            btnHtml?.setTextColor(onText)
            btnCss?.background = offBg
            btnCss?.setTextColor(ink)
        } else {
            btnCss?.background = onBg
            btnCss?.setTextColor(onText)
            btnHtml?.background = offBg
            btnHtml?.setTextColor(ink)
        }
        // дозапросить данные для нового режима
        if (cssPath.isNotEmpty()) refreshTargetData()
    }

    /** Подписка на события моста на время показа листа. */
    override fun onShown() {
        InspectorBridge.onEvent = ::onBridgeEvent
        // модуль мог быть потерян при переходе на другую страницу
        runJs(InspectorBridge.injectModule()) { }
    }

    override fun onDismiss() {
        InspectorBridge.onEvent = null
        runJs(InspectorBridge.stopPickScript()) { }
        target = null
        code = null
        btnHtml = null
        btnCss = null
    }

    private fun startPick() {
        runJs(InspectorBridge.injectModule()) { r ->
            post {
                if (r.contains("ERR")) {
                    toast("Не удалось подготовить инспектор: $r")
                    return@post
                }
                runJs(InspectorBridge.pickScript()) { r2 ->
                    post {
                        if (r2.contains("NO_MOD")) {
                            toast("Страница не готова к инспектору")
                        } else if (r2.contains("ERR")) {
                            toast("Ошибка: $r2")
                        } else {
                            toast(getString(R.string.inspector_armed))
                        }
                    }
                }
            }
        }
    }

    private fun stopPick() {
        runJs(InspectorBridge.stopPickScript()) { }
        toast(getString(R.string.inspector_off))
    }

    private fun onBridgeEvent(ev: String, fields: Array<String>) {
        post {
            when (ev) {
                InspectorBridge.EV_PICK -> {
                    val path = fields.getOrElse(0) { "" }
                    val html = fields.getOrElse(1) { "" }
                    val css = fields.getOrElse(2) { "" }
                    cssPath = path
                    target?.text = getString(R.string.inspector_target, path)
                    when (mode) {
                        MODE_CSS -> code?.setText(css)
                        else -> code?.setText(html)
                    }
                }
                InspectorBridge.EV_CSS_RESULT -> {
                    val json = fields.getOrElse(0) { "" }
                    val obj = try {
                        if (json.isEmpty()) null else org.json.JSONObject(json)
                    } catch (e: Exception) { null }
                    if (obj == null) {
                        if (code?.text?.isEmpty() != false) {
                            code?.setText("")
                        }
                    } else {
                        val v = when (mode) {
                            MODE_CSS -> obj.optString("css", "")
                            else -> obj.optString("html", "")
                        }
                        code?.setText(v)
                    }
                }
            }
        }
    }

    /** Запросить свежие данные по текущему cssPath. */
    private fun refreshTargetData() {
        if (cssPath.isEmpty()) return
        runJs(InspectorBridge.snapshotScript(cssPath)) { }
    }

    private fun applyEdits() {
        if (cssPath.isEmpty()) {
            toast(getString(R.string.inspector_no_target))
            return
        }
        val text = code?.text?.toString() ?: ""
        val js = when (mode) {
            MODE_CSS -> InspectorBridge.applyCssScript(cssPath, text)
            else -> InspectorBridge.applyHtmlScript(cssPath, text)
        }
        runJs(js) { r ->
            post {
                when {
                    r.contains("NOT_FOUND") ->
                        toast(getString(R.string.inspector_apply_fail, "элемент не найден"))
                    r.contains("BAD_HTML") ->
                        toast(getString(R.string.inspector_apply_fail, "неверный HTML"))
                    r.contains("ERR") ->
                        toast(getString(R.string.inspector_apply_fail, r))
                    r.contains("OK") -> {
                        toast(getString(R.string.inspector_applied))
                        // после замены узла — переснять путь/данные не получится (узла нет),
                        // поэтому сбрасываем цель: пользователь выберет заново
                        if (mode == MODE_HTML) {
                            cssPath = ""
                            target?.text = getString(R.string.inspector_no_target)
                        } else {
                            refreshTargetData()
                        }
                    }
                    else -> toast(getString(R.string.inspector_apply_fail, r))
                }
            }
        }
    }

    private fun copyCode() {
        val text = code?.text?.toString() ?: ""
        if (text.isEmpty()) return
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("glass", text))
            Toast.makeText(activity, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getString(res: Int): String = activity.getString(res)
    private fun getString(res: Int, arg: String): String = activity.getString(res, arg)

    companion object {
        private const val MODE_HTML = "html"
        private const val MODE_CSS = "css"
    }
}
