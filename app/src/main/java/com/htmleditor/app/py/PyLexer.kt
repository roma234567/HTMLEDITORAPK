package com.htmleditor.app.py

enum class TT {
    NEWLINE, INDENT, DEDENT, NAME, KEYWORD, NUMBER, STRING, OP, EOF
}

class Token(
    val type: TT,
    val value: String,
    val line: Int,
    val strPrefix: String = "",
    val raw: String = ""
)

/**
 * Токенизатор Python: отступы (INDENT/DEDENT), переводы строк,
 * строки (в т.ч. многострочные и f-строки), числа, ключевые слова, операторы.
 */
class PyLexer(private val src: String) {

    private val tokens = ArrayList<Token>()
    private var i = 0
    private var line = 1
    private var parenDepth = 0
    private val indentStack = ArrayList<Int>().apply { add(0) }
    private var atLineStart = true
    private var lineHasContent = false

    private val keywords = setOf(
        "if", "else", "elif", "while", "for", "in", "def", "return", "break",
        "continue", "pass", "import", "from", "as", "and", "or", "not",
        "True", "False", "None", "is", "lambda", "class", "try", "except",
        "finally", "with", "yield", "global", "nonlocal", "assert", "del",
        "raise", "async", "await"
    )

    private val ops = listOf(
        "**=", "//=", "...",
        "**", "//", "<=", ">=", "==", "!=", "<<", ">>", "+=", "-=", "*=", "/=", "%=",
        "+", "-", "*", "/", "%", "<", ">", "(", ")", "[", "]", "{", "}", ",",
        ":", ".", ";", "=", "~", "^", "&", "|", "@"
    )

    fun tokenize(): List<Token> {
        while (i < src.length) {
            if (atLineStart && parenDepth == 0) {
                if (!handleLineStart()) continue
            }
            val c = src[i]
            when {
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '\n' -> {
                    i++
                    line++
                    if (parenDepth == 0) {
                        if (lineHasContent) {
                            emit(TT.NEWLINE, "\\n")
                            lineHasContent = false
                        }
                        atLineStart = true
                    }
                }
                c == '\\' && i + 1 < src.length && src[i + 1] == '\n' -> {
                    i += 2
                    line++
                }
                c == '#' -> skipComment()
                c == '(' || c == '[' || c == '{' -> {
                    parenDepth++
                    op(c.toString())
                    i++
                }
                c == ')' || c == ']' || c == '}' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    op(c.toString())
                    i++
                }
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> number()
                c.isLetter() || c == '_' -> nameOrString()
                else -> operator()
            }
        }

        if (parenDepth > 0) throw PyError(line, "незакрытая скобка")
        if (lineHasContent) emit(TT.NEWLINE, "\\n")
        while (indentStack.size > 1) {
            indentStack.removeAt(indentStack.size - 1)
            tokens.add(Token(TT.DEDENT, "", line))
        }
        tokens.add(Token(TT.EOF, "", line))
        return tokens
    }

    /** true — можно продолжать разбор строки; false — строка пропущена. */
    private fun handleLineStart(): Boolean {
        var col = 0
        var j = i
        while (j < src.length && (src[j] == ' ' || src[j] == '\t')) {
            col += if (src[j] == '\t') 4 - (col % 4) else 1
            j++
        }
        // Пустая строка или комментарий — не влияет на отступы
        if (j >= src.length) {
            i = j
            return false
        }
        if (src[j] == '\n') {
            i = j
            return false
        }
        if (src[j] == '#') {
            i = j
            skipComment()
            return false
        }

        i = j
        atLineStart = false
        val top = indentStack.last()
        when {
            col > top -> {
                indentStack.add(col)
                tokens.add(Token(TT.INDENT, "", line))
            }
            col < top -> {
                while (indentStack.size > 1 && indentStack.last() > col) {
                    indentStack.removeAt(indentStack.size - 1)
                    tokens.add(Token(TT.DEDENT, "", line))
                }
                if (indentStack.last() != col) {
                    throw PyError(line, "непоследовательный отступ")
                }
            }
        }
        return true
    }

    private fun skipComment() {
        while (i < src.length && src[i] != '\n') i++
    }

    private fun emit(type: TT, value: String, prefix: String = "", raw: String = "") {
        tokens.add(Token(type, value, line, prefix, raw))
        lineHasContent = true
    }

    // --------------------------------------------------------------- числа

    private fun number() {
        val start = i
        if (src[i] == '0' && i + 1 < src.length && src[i + 1].lowercaseChar() in listOf('x', 'b', 'o')) {
            val kind = src[i + 1].lowercaseChar()
            val radix = when (kind) {
                'x' -> 16; 'b' -> 2; else -> 8
            }
            i += 2
            val ds = i
            while (i < src.length && src[i].isLetterOrDigit()) i++
            val digits = src.substring(ds, i)
            val v = digits.toLongOrNull(radix)
                ?: throw PyError(line, "неверная цифра в числе ${src.substring(start, i)}")
            emit(TT.NUMBER, v.toString())
            return
        }
        while (i < src.length && src[i].isDigit()) i++
        var isFloat = false
        if (i + 1 < src.length && src[i] == '.' && src[i + 1] != '.') {
            isFloat = true
            i++
            while (i < src.length && src[i].isDigit()) i++
        } else if (i < src.length && src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit()) {
            isFloat = true
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            val save = i
            var j = i + 1
            if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
            if (j < src.length && src[j].isDigit()) {
                isFloat = true
                i = j
                while (i < src.length && src[i].isDigit()) i++
            } else {
                i = save
            }
        }
        val text = src.substring(start, i)
        if (isFloat) {
            val d = text.toDoubleOrNull() ?: throw PyError(line, "неверное число $text")
            emit(TT.NUMBER, d.toString())
        } else {
            val v = text.toLongOrNull()
                ?: text.toDoubleOrNull()?.toString()
                ?: throw PyError(line, "неверное число $text")
            emit(TT.NUMBER, v.toString())
        }
    }

    // ---------------------------------------- имена / ключевые слова / строки

    private fun nameOrString() {
        // Возможный префикс строки: r b f rb fr и т.п.
        val prefixEnd = stringPrefixEnd()
        if (prefixEnd >= 0) {
            string(prefixEnd)
            return
        }
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        val word = src.substring(start, i)
        if (word in keywords) emit(TT.KEYWORD, word) else emit(TT.NAME, word)
    }

    /** Если по позиции i начинается строка — возвращает длину префикса, иначе -1. */
    private fun stringPrefixEnd(): Int {
        var j = i
        var letters = 0
        while (j < src.length && letters < 3 && src[j].isLetter()) {
            if (src[j].lowercaseChar() !in charArrayOf('r', 'b', 'f')) return -1
            letters++
            j++
        }
        if (letters == 0) return -1
        if (j < src.length && (src[j] == '"' || src[j] == '\'')) return letters
        return -1
    }

    private fun string(prefixLen: Int) {
        val prefix = src.substring(i, i + prefixLen).lowercase()
        i += prefixLen
        val quote = src[i]
        val triple = i + 2 < src.length && src[i + 1] == quote && src[i + 2] == quote
        if (triple) i += 3 else i += 1

        val sb = StringBuilder()
        var closed = false
        while (i < src.length) {
            val c = src[i]
            if (triple && c == quote && i + 2 < src.length && src[i + 1] == quote && src[i + 2] == quote) {
                i += 3
                closed = true
                break
            }
            if (!triple && c == quote) {
                i++
                closed = true
                break
            }
            if (!triple && c == '\n') throw PyError(line, "незакрытая строка")
            if (c == '\\' && 'r' !in prefix && i + 1 < src.length) {
                sb.append(readEscape())
            } else {
                if (c == '\n') line++
                sb.append(c)
                i++
            }
        }
        if (!closed) throw PyError(line, "незакрытая строка")
        emit(TT.STRING, sb.toString(), prefix, sb.toString())
    }

    private fun readEscape(): Char {
        i++ // пропускаем бэкслэш
        val c = src[i]
        i++
        return when (c) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '0' -> '\u0000'
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'v' -> '\u000B'
            '\\' -> '\\'
            '\'' -> '\''
            '"' -> '"'
            '\n' -> { line++; '\n' }
            'u', 'U', 'x' -> {
                val len = when (c) {
                    'x' -> 2; 'U' -> 8; else -> 4
                }
                val hex = if (i + len <= src.length) src.substring(i, i + len) else ""
                i += len
                val code = hex.toIntOrNull(16)
                if (code != null) code.toChar() else c
            }
            else -> c
        }
    }

    // ----------------------------------------------------------- операторы

    private fun operator() {
        for (o in ops) {
            if (src.startsWith(o, i)) {
                i += o.length
                op(o)
                return
            }
        }
        throw PyError(line, "неожиданный символ '${src[i]}'")
    }

    private fun op(o: String) = emit(TT.OP, o)
}
