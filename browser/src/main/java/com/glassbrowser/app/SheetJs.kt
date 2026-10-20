package com.glassbrowser.app

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Выполнение JS на текущей странице, сниппеты (в т.ч. авто-запуск),
 * проба WebSQL, результат выполнения.
 */
class SheetJs(activity: MainActivity) : BaseSheet(activity, R.layout.sheet_js) {

    private var codeEdit: EditText? = null
    private var nameEdit: EditText? = null
    private var resultView: TextView? = null
    private var list: LinearLayout? = null

    override fun setup(view: View) {
        codeEdit = view.findViewById(R.id.jsCode)
        nameEdit = view.findViewById(R.id.jsName)
        resultView = view.findViewById(R.id.jsResult)
        list = view.findViewById(R.id.jsList)

        view.findViewById<Button>(R.id.btnJsRun).setOnClickListener { runCode() }
        view.findViewById<Button>(R.id.btnJsWebsql).setOnClickListener { webSqlProbe() }
        view.findViewById<Button>(R.id.btnJsSave).setOnClickListener { saveSnippet() }
        view.findViewById<Button>(R.id.btnSheetClose).setOnClickListener { close() }

        rebuildList()
    }

    private fun runCode() {
        val code = codeEdit?.text?.toString() ?: ""
        if (code.isBlank()) {
            toast(getString(R.string.js_empty))
            return
        }
        evaluate(code)
    }

    private fun evaluate(code: String) {
        val js = InspectorBridge.runUserCode(code)
        runJs(js) { raw ->
            post {
                val pretty = prettyResult(raw)
                resultView?.text = pretty
                LogHub.log(LogHub.WEB, "js → ${pretty.take(200)}")
            }
        }
    }

    private fun webSqlProbe() {
        val js = InspectorBridge.runUserCode(probeWebSqlBody())
        runJs(js) { raw ->
            post {
                val pretty = prettyResult(raw)
                resultView?.text = "WebSQL: " + pretty
            }
        }
    }

    private fun probeWebSqlBody(): String {
        return (
            "var out = 'openDatabase: ';\n" +
                "try {\n" +
                "  if (typeof openDatabase !== 'function') { out += 'отсутствует (отключено в WebView)'; }\n" +
                "  else {\n" +
                "    var db = openDatabase('probe_db', '1.0', 'probe', 1024);\n" +
                "    db.transaction(function(t){ t.executeSql('CREATE TABLE IF NOT EXISTS p (id INTEGER)'); });\n" +
                "    out += 'доступна, таблица создана';\n" +
                "  }\n" +
                "} catch(e) { out += 'ошибка: ' + e; }\n" +
                "return out;"
            )
    }

    private fun prettyResult(raw: String?): String {
        if (raw.isNullOrBlank()) return "(null)"
        return try {
            val o = JSONObject(raw)
            if (o.optInt("ok", 0) == 1) o.optString("v", "") else "Ошибка: " + o.optString("e", "?")
        } catch (e: Exception) {
            raw
        }
    }

    private fun saveSnippet() {
        val code = codeEdit?.text?.toString() ?: ""
        if (code.isBlank()) {
            toast(getString(R.string.js_empty))
            return
        }
        var name = nameEdit?.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) name = "snip-" + (PrefsX.snippets().size + 1)
        val existing = PrefsX.snippets().firstOrNull { it.optString("name") == name }
        val auto = existing?.optBoolean("auto", false) ?: false
        PrefsX.snippetPut(name, code, auto)
        rebuildList()
        toast(getString(R.string.js_saved))
    }

    private fun rebuildList() {
        val container = list ?: return
        container.removeAllViews()
        val snippets = PrefsX.snippets()
        if (snippets.isEmpty()) {
            val tv = TextView(activity)
            tv.text = "—"
            tv.textSize = 12f
            tv.setTextColor(activity.colorOf(R.color.ink2))
            tv.setPadding(8, 8, 8, 8)
            container.addView(tv)
            return
        }
        for (o in snippets) {
            val name = o.optString("name")
            val code = o.optString("code")
            val auto = o.optBoolean("auto", false)

            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(6, 4, 6, 4)

            val tv = TextView(activity)
            tv.text = name
            tv.textSize = 13f
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setTextColor(activity.colorOf(R.color.ink))
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.setOnClickListener {
                codeEdit?.setText(code)
                nameEdit?.setText(name)
            }
            tv.setOnLongClickListener {
                evaluate(code)
                true
            }
            row.addView(tv)

            val chk = CheckBox(activity)
            chk.isChecked = auto
            chk.text = getString(R.string.js_auto)
            chk.textSize = 12f
            chk.setTextColor(activity.colorOf(R.color.ink2))
            chk.setButtonDrawable(android.R.drawable.checkbox_on_background)
            chk.setOnCheckedChangeListener { _, checked ->
                PrefsX.snippetSetAuto(name, checked)
            }
            row.addView(chk)

            val play = ImageButton(activity)
            play.setImageResource(R.drawable.ic_play)
            play.background = null
            play.setColorFilter(activity.colorOf(R.color.accent))
            val pad = dp(8)
            play.setPadding(pad, pad, pad, pad)
            play.contentDescription = getString(R.string.run)
            play.setOnClickListener { evaluate(code) }
            row.addView(play)

            val del = ImageButton(activity)
            del.setImageResource(R.drawable.ic_trash)
            del.background = null
            del.setColorFilter(activity.colorOf(R.color.danger))
            del.setPadding(pad, pad, pad, pad)
            del.contentDescription = getString(R.string.delete)
            del.setOnClickListener {
                PrefsX.snippetRemove(name)
                rebuildList()
            }
            row.addView(del)

            container.addView(row)
        }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private fun getString(res: Int): String = activity.getString(res)

    override fun onDismiss() {
        codeEdit = null
        nameEdit = null
        resultView = null
        list = null
    }
}
