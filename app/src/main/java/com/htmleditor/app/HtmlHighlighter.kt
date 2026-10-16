package com.htmleditor.app

import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

/**
 * Простая подсветка HTML/CSS/JS: теги, атрибуты, строки, комментарии и DOCTYPE.
 * Цвета приходят из ресурсов, поэтому подстраиваются под светлую/тёмную тему.
 * Работает только со span'ами, не меняя текст — курсор не сбивается.
 */
object HtmlHighlighter {

    /** Выше этого размера подсветка отключается ради экономии памяти и времени. */
    private const val MAX_LENGTH = 150_000

    data class Colors(
        val tag: Int,
        val attr: Int,
        val string: Int,
        val comment: Int
    )

    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val DOCTYPE = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("</?[A-Za-z][^<>]*>")
    private val ATTR = Regex(
        "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?"
    )

    private var running = false

    fun apply(s: Editable, colors: Colors) {
        if (running) return
        if (s.length == 0 || s.length > MAX_LENGTH) return
        running = true
        try {
            clearSpans(s)

            val commentRanges = ArrayList<IntRange>()
            for (m in COMMENT.findAll(s)) {
                commentRanges.add(m.range)
                color(s, m.range.first, m.range.last + 1, colors.comment)
            }

            for (m in DOCTYPE.findAll(s)) {
                if (overlaps(m.range, commentRanges)) continue
                color(s, m.range.first, m.range.last + 1, colors.tag)
            }

            for (m in TAG.findAll(s)) {
                if (overlaps(m.range, commentRanges)) continue
                highlightTag(s, m.range.first, m.value, colors)
            }
        } finally {
            running = false
        }
    }

    private fun highlightTag(s: Editable, start: Int, tag: String, colors: Colors) {
        // Имя тега: <name или </name
        var i = 1
        if (tag.length > 1 && tag[1] == '/') i = 2
        var nameEnd = i
        while (nameEnd < tag.length &&
            (tag[nameEnd].isLetterOrDigit() || tag[nameEnd] == '-' ||
                tag[nameEnd] == '_' || tag[nameEnd] == ':')
        ) {
            nameEnd++
        }
        if (nameEnd == i) return

        color(s, start, start + nameEnd, colors.tag)
        s.setSpan(
            StyleSpan(Typeface.BOLD),
            start + i,
            start + nameEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Атрибуты — между именем тега и закрывающей скобкой
        val bodyStart = start + nameEnd
        val bodyEnd = start + tag.length - 1
        if (bodyEnd <= bodyStart) return
        val body = tag.substring(nameEnd, tag.length - 1)

        for (m in ATTR.findAll(body)) {
            val nameRange = m.groups[1]?.range ?: continue
            color(s, bodyStart + nameRange.first, bodyStart + nameRange.last + 1, colors.attr)

            val valueRange = m.groups[3]?.range
            if (valueRange != null) {
                color(
                    s,
                    bodyStart + valueRange.first,
                    bodyStart + valueRange.last + 1,
                    colors.string
                )
            }
        }
    }

    private fun color(s: Editable, start: Int, end: Int, color: Int) {
        if (start < 0 || end > s.length || start >= end) return
        s.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun clearSpans(s: Editable) {
        for (span in s.getSpans(0, s.length, ForegroundColorSpan::class.java)) s.removeSpan(span)
        for (span in s.getSpans(0, s.length, StyleSpan::class.java)) s.removeSpan(span)
        // BackgroundColorSpan (подсветка поиска) намеренно не трогаем
    }

    private fun overlaps(range: IntRange, others: List<IntRange>): Boolean {
        for (other in others) {
            if (range.first <= other.last && other.first <= range.last) return true
        }
        return false
    }
}
