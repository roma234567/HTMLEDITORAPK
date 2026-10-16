package com.htmleditor.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Работа с файлами через Storage Access Framework (без разрешений на хранилище). */
object FileStore {

    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        }
    } catch (e: Exception) {
        null
    }

    fun write(context: Context, uri: Uri, text: String): Boolean {
        return try {
            val stream = try {
                context.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                null
            } ?: context.contentResolver.openOutputStream(uri)
            if (stream == null) return false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Сохраняем права на файл, чтобы можно было перезаписать его позже. */
    fun persistPermissions(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Провайдер не выдаёт persistable-прав — файл доступен в рамках сессии.
        }
    }
}
