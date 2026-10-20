package com.glassbrowser.app

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Локальная SQL-консоль: запросы к SQLite приложения + внедрение
 * SELECT-результата в страницу (window.__glassSql).
 */
class SheetSql(activity: MainActivity) : BaseSheet(activity, R.layout.sheet_sql) {

    private var codeEdit: EditText? = null
    private var resultView: TextView? = null

    private var store: SqlStore? = null
    private var lastResult: SqlStore.Result? = null

    override fun setup(view: View) {
        codeEdit = view.findViewById(R.id.sqlCode)
        resultView = view.findViewById(R.id.sqlResult)
        store = SqlStore(activity)

        view.findViewById<Button>(R.id.btnSqlRun).setOnClickListener { runSql() }
        view.findViewById<Button>(R.id.btnSqlInject).setOnClickListener { inject() }
        view.findViewById<Button>(R.id.btnSheetClose).setOnClickListener { close() }

        codeEdit?.setText("SELECT * FROM notes LIMIT 20")
    }

    private fun runSql() {
        val sql = codeEdit?.text?.toString() ?: ""
        val s = store ?: return
        val res = s.run(sql)
        lastResult = res
        resultView?.text = res.text()
    }

    private fun inject() {
        // сначала свежий прогон
        val sql = codeEdit?.text?.toString() ?: ""
        val res = store?.run(sql) ?: return
        lastResult = res
        resultView?.text = res.text()

        if (!res.select) {
            toast(getString(R.string.sql_select_only))
            return
        }
        val json = res.json()
        if (json == "null") {
            toast(getString(R.string.sql_select_only))
            return
        }
        val js = InspectorBridge.injectSqlScript(json)
        runJs(js) { r ->
            post {
                if (r.contains("OK")) {
                    toast(getString(R.string.sql_injected))
                } else {
                    toast("Ошибка внедрения: $r")
                }
            }
        }
    }

    private fun getString(res: Int): String = activity.getString(res)

    override fun onDismiss() {
        codeEdit = null
        resultView = null
        try { store?.close() } catch (e: Exception) { /* ignore */ }
        store = null
        lastResult = null
    }
}
