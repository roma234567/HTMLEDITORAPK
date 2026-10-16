package com.htmleditor.app

import android.content.Context

/** Хранение состояния приложения: черновик, файл, настройки предпросмотра. */
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

        private const val KEY_DRAFT = "draft"
        private const val KEY_SAVED = "saved_code"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_FILE_URI = "file_uri"
        private const val KEY_MODE = "preview_mode"
        private const val KEY_ZOOM = "preview_zoom"
        private const val KEY_TAB = "tab_index"
    }
}
