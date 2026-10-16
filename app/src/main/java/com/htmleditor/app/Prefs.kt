package com.htmleditor.app

import android.content.Context

/** Хранение состояния приложения: вкладки файлов, настройки, черновик. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("html_editor_state", Context.MODE_PRIVATE)

    var draft: String?
        get() = sp.getString(KEY_DRAFT, null)
        set(value) = sp.edit().putString(KEY_DRAFT, value).apply()

    var savedCode: String?
        get() = sp.getString(KEY_SAVED, null)
        set(value) = sp.edit().putString(KEY_SAVED, value).apply()

    var fileName: String?
        get() = sp.getString(KEY_FILE_NAME, null)
        set(value) = sp.edit().putString(KEY_FILE_NAME, value).apply()

    var fileUri: String?
        get() = sp.getString(KEY_FILE_URI, null)
        set(value) = sp.edit().putString(KEY_FILE_URI, value).apply()

    var tabsJson: String?
        get() = sp.getString(KEY_TABS_JSON, null)
        set(value) = sp.edit().putString(KEY_TABS_JSON, value).apply()

    var activeTab: Int
        get() = sp.getInt(KEY_ACTIVE_TAB, 0)
        set(value) = sp.edit().putInt(KEY_ACTIVE_TAB, value).apply()

    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_PHONE) ?: MODE_PHONE
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    var zoom: Int
        get() = sp.getInt(KEY_ZOOM, 100)
        set(value) = sp.edit().putInt(KEY_ZOOM, value).apply()

    var tabIndex: Int
        get() = sp.getInt(KEY_TAB, 0)
        set(value) = sp.edit().putInt(KEY_TAB, value).apply()

    companion object {
        const val MODE_PHONE = "phone"
        const val MODE_PC = "pc"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val KEY_DRAFT = "draft"
        private const val KEY_SAVED = "saved_code"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_FILE_URI = "file_uri"
        private const val KEY_TABS_JSON = "tabs_json"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MODE = "preview_mode"
        private const val KEY_ZOOM = "preview_zoom"
        private const val KEY_TAB = "tab_index"
    }
}
