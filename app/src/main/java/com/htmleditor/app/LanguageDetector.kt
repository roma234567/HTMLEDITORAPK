package com.htmleditor.app

/**
 * Автоопределение языка: расширение файла, затем эвристика по содержимому.
 * HTML — по умолчанию, Python — только при явных признаках.
 */
object LanguageDetector {

    private val HTML_MARKERS = Regex(
        "(?is)<!doctype\\s+html|<html|<head|<body|<div|<script|<style|<span|<p[ >]|<a\\s+href|<h[1-6][ >]|<input[ >]|<img[ >]"
    )

    private val PY_MARKERS = Regex(
        "(?m)^\\s*(from\\s+[A-Za-z_]\\w*\\s+import|import\\s+[A-Za-z_]\\w*|def\\s+[A-Za-z_]\\w*\\s*\\(|" +
            "if\\s+__name__\\s*==|print\\s*\\(|input\\s*\\(|turtle\\.|from\\s+turtle\\s+import|" +
            "for\\s+\\w+\\s+in\\s+range\\s*\\(|while\\s+.*:|class\\s+[A-Za-z_]\\w*\\s*[(:])"
    )

    /** true — Python, false — HTML. */
    fun isPython(fileName: String?, code: String): Boolean {
        val name = fileName?.lowercase()
        if (name != null) {
            when {
                name.endsWith(".py") || name.endsWith(".pyw") -> return true
                name.endsWith(".html") || name.endsWith(".htm") ||
                    name.endsWith(".xhtml") || name.endsWith(".svg") -> return false
            }
        }
        // Явные HTML-признаки имеют приоритет
        if (HTML_MARKERS.containsMatchIn(code)) return false
        // Пустой код — считаем HTML (стартовый шаблон)
        if (code.isBlank()) return false
        return PY_MARKERS.containsMatchIn(code)
    }
}
