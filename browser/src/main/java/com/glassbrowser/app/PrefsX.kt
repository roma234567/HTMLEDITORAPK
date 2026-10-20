package com.glassbrowser.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Локальные настройки Glass Browser: тема, режим ПК, перехват запросов,
 * последний URL и сниппеты JS.
 */
object PrefsX {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext
                .getSharedPreferences("glass_prefs", Context.MODE_PRIVATE)
        }
    }

    var night: Boolean
        get() = sp.getBoolean("night", false)
        set(v) { sp.edit().putBoolean("night", v).apply() }

    var desktopMode: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    var interceptOn: Boolean
        get() = sp.getBoolean("intercept", true)
        set(v) { sp.edit().putBoolean("intercept", v).apply() }

    var proxyAutoStart: Boolean
        get() = sp.getBoolean("proxy_auto", false)
        set(v) { sp.edit().putBoolean("proxy_auto", v).apply() }

    var lastUrl: String
        get() = sp.getString("last_url", "") ?: ""
        set(v) { sp.edit().putString("last_url", v).apply() }

    // ---------- сниппеты JS ----------

    private const val SNIPPETS = "snippets"

    private fun snippetArray(): JSONArray {
        val raw = sp.getString(SNIPPETS, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    private fun writeSnippets(arr: JSONArray) {
        sp.edit().putString(SNIPPETS, arr.toString()).apply()
    }

    fun snippets(): List<JSONObject> {
        val arr = snippetArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o)
        }
        return out
    }

    fun snippetPut(name: String, code: String, auto: Boolean = false) {
        val arr = snippetArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) {
                o.put("code", code)
                o.put("auto", auto)
                writeSnippets(arr)
                return
            }
        }
        val o = JSONObject()
        o.put("name", name)
        o.put("code", code)
        o.put("auto", auto)
        arr.put(o)
        writeSnippets(arr)
    }

    fun snippetSetAuto(name: String, auto: Boolean) {
        val arr = snippetArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) {
                o.put("auto", auto)
                writeSnippets(arr)
                return
            }
        }
    }

    fun snippetRemove(name: String) {
        val arr = snippetArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) out.put(o)
        }
        writeSnippets(out)
    }
}
