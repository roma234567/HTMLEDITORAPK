package com.htmleditor.app

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView

/**
 * Редактор кода: подсветка синтаксиса, номера строк и синхронная прокрутка.
 */
class CodeEditor(private val input: EditText, private val gutter: TextView) {

    private var lastLineCount = -1

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) {
                if (s != null) refresh(s)
            }
        })

        input.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            gutter.scrollTo(0, scrollY)
        }
    }

    private fun refresh(s: Editable) {
        updateGutter(s)
        HtmlHighlighter.apply(s)
    }

    private fun updateGutter(s: Editable) {
        var lines = 1
        for (i in 0 until s.length) {
            if (s[i] == '\n') lines++
        }
        if (lines == lastLineCount) return
        lastLineCount = lines

        val sb = StringBuilder(lines * 6)
        for (i in 1..lines) {
            sb.append(i)
            if (i < lines) sb.append('\n')
        }
        gutter.text = sb.toString()
        gutter.scrollTo(0, input.scrollY)
    }

    fun getText(): String = input.text.toString()

    fun setText(text: String) {
        lastLineCount = -1
        input.setText(text)
        input.setSelection(0)
        input.scrollTo(0, 0)
        gutter.scrollTo(0, 0)
    }
}
