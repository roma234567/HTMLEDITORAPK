package com.htmleditor.app

import android.net.Uri

/**
 * Открытый файл в редакторе. Содержимое хранится в памяти (включая
 * несохранённые правки), поэтому при переключении вкладок файл
 * не загружается с диска заново.
 * lang: auto / html / python — выбор пользователя для файлов без
 * расширения .py/.html (иначе определяется автоматически).
 */
class FileTab(
    var uri: Uri?,
    var name: String?,
    var content: String,
    var saved: String,
    var lang: String = "auto"
)
