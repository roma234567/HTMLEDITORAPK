package com.htmleditor.app.py

/** Рекурсивный спуск: токены → AST. lineShift нужен для под-разбора f-строк. */
class PyParser(private val tokens: List<Token>, private val lineShift: Int = 0) {

    private var pos = 0

    // ------------------------------------------------------------- токены

    private fun peek(k: Int = 0): Token =
        tokens[(pos + k).coerceAtMost(tokens.size - 1)]

    private fun next(): Token {
        val t = peek()
        if (pos < tokens.size - 1) pos++
        return t
    }

    private fun at(type: TT, value: String? = null): Boolean {
        val t = peek()
        return t.type == type && (value == null || t.value == value)
    }

    private fun atKw(kw: String) = at(TT.KEYWORD, kw)
    private fun atOp(op: String) = at(TT.OP, op)

    private fun accept(type: TT, value: String? = null): Boolean {
        if (at(type, value)) {
            next()
            return true
        }
        return false
    }

    private fun acceptKw(kw: String) = accept(TT.KEYWORD, kw)
    private fun acceptOp(op: String) = accept(TT.OP, op)

    private fun expect(type: TT, value: String? = null, what: String = ""): Token {
        if (!at(type, value)) err("ожидалось ${if (what.isNotEmpty()) what else value ?: type}")
        return next()
    }

    private fun line() = peek().line + lineShift

    private fun err(msg: String): Nothing = throw PyError(line(), msg)

    private fun <T : Node> T.withLine(): T {
        line = line()
        return this
    }

    // ------------------------------------------------------------ программа

    fun parseModule(): List<Stmt> {
        val list = parseStmtList(false)
        if (!at(TT.EOF)) err("лишний код в программе")
        return list
    }

    fun parseSingleExpr(): Expr {
        val e = parseExpr()
        if (!at(TT.EOF)) err("неожиданный символ в выражении")
        return e
    }

    private fun parseStmtList(inBlock: Boolean): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (true) {
            when {
                at(TT.EOF) -> break
                inBlock && at(TT.DEDENT) -> break
                accept(TT.NEWLINE) -> continue
                isCompoundStart() -> out.add(parseCompound())
                else -> {
                    out.add(parseSimple())
                    if (!accept(TT.NEWLINE) && !at(TT.EOF)) {
                        err("ожидался перевод строки")
                    }
                }
            }
        }
        if (inBlock) {
            if (!at(TT.DEDENT)) err("ожидался конец блока с отступом")
            next()
        }
        return out
    }

    private fun isCompoundStart(): Boolean =
        atKw("if") || atKw("while") || atKw("for") || atKw("def")

    // ------------------------------------------------------------ инструкции

    private fun parseCompound(): Stmt = when {
        atKw("if") -> parseIf()
        atKw("while") -> {
            val l = line()
            next()
            val cond = parseExpr()
            val body = parseBody()
            WhileStmt(cond, body).withLine().also { it.line = l }
        }
        atKw("for") -> {
            val l = line()
            next()
            val target = parseExprList()
            expect(TT.KEYWORD, "in", "'in'")
            val iter = parseExpr()
            val body = parseBody()
            ForStmt(target, iter, body).also { it.line = l }
        }
        atKw("def") -> parseDef()
        else -> err("неожиданное начало инструкции")
    }

    private fun parseIf(): Stmt {
        val l = line()
        next() // if
        val cond = parseExpr()
        val thenBody = parseBody()
        val elifs = ArrayList<Pair<Expr, List<Stmt>>>()
        var elseBody: List<Stmt>? = null
        while (atKw("elif")) {
            next()
            val c = parseExpr()
            elifs.add(c to parseBody())
        }
        if (atKw("else")) {
            next()
            elseBody = parseBody()
        }
        return IfStmt(cond, thenBody, elifs, elseBody).also { it.line = l }
    }

    private fun parseDef(): Stmt {
        val l = line()
        next() // def
        val name = expect(TT.NAME, null, "имя функции").value
        expect(TT.OP, "(", "'('")
        val params = ArrayList<ParamNode>()
        if (!atOp(")")) {
            while (true) {
                val pName = expect(TT.NAME, null, "имя параметра").value
                var default: Expr? = null
                if (acceptOp("=")) default = parseExpr()
                params.add(ParamNode(pName, default))
                if (!acceptOp(",")) break
                if (atOp(")")) break
            }
        }
        expect(TT.OP, ")", "')'")
        val body = parseBody()
        return FuncStmt(name, params, body).also { it.line = l }
    }

    /** Блок после «:» — либо NEWLINE + INDENT…DEDENT, либо одно инструкция на строке. */
    private fun parseBody(): List<Stmt> {
        if (!acceptOp(":")) err("ожидалось ':'")
        if (accept(TT.NEWLINE)) {
            if (!at(TT.INDENT)) err("ожидался отступ после ':'")
            next()
            return parseStmtList(true)
        }
        return listOf(parseSimple())
    }

    private fun parseSimple(): Stmt {
        val l = line()
        return when {
            atKw("return") -> {
                next()
                val stmt = if (at(TT.NEWLINE) || at(TT.EOF) || at(TT.DEDENT)) {
                    ReturnStmt(null)
                } else {
                    ReturnStmt(parseExprList())
                }
                stmt.also { it.line = l }
            }
            atKw("break") -> BreakStmt().also { next(); it.line = l }
            atKw("continue") -> ContinueStmt().also { next(); it.line = l }
            atKw("pass") -> PassStmt().also { next(); it.line = l }
            atKw("import") -> parseImport(l)
            atKw("from") -> parseFromImport(l)
            atKw("lambda") -> err("лямбда-функции не поддерживаются")
            atKw("class") -> err("классы не поддерживаются")
            atKw("try") || atKw("with") || atKw("yield") || atKw("global") ||
                atKw("assert") || atKw("del") || atKw("raise") || atKw("async") ->
                err("инструкция «${peek().value}» пока не поддерживается")
            else -> parseExprOrAssign(l)
        }
    }

    private fun parseImport(l: Int): Stmt {
        next() // import
        val first = expect(TT.NAME, null, "имя модуля").value
        val sb = StringBuilder(first)
        while (atOp(".")) {
            next()
            sb.append('.').append(expect(TT.NAME, null, "имя модуля").value)
        }
        var alias: String? = null
        if (acceptKw("as")) alias = expect(TT.NAME, null, "имя").value
        return ImportStmt(sb.toString(), alias).also { it.line = l }
    }

    private fun parseFromImport(l: Int): Stmt {
        next() // from
        val first = expect(TT.NAME, null, "имя модуля").value
        val sb = StringBuilder(first)
        while (atOp(".")) {
            next()
            sb.append('.').append(expect(TT.NAME, null, "имя модуля").value)
        }
        expect(TT.KEYWORD, "import", "'import'")
        if (acceptOp("*")) {
            return FromImportStmt(sb.toString(), emptyList(), star = true).also { it.line = l }
        }
        val names = ArrayList<Pair<String, String?>>()
        while (true) {
            val n = expect(TT.NAME, null, "имя").value
            var alias: String? = null
            if (acceptKw("as")) alias = expect(TT.NAME, null, "имя").value
            names.add(n to alias)
            if (!acceptOp(",")) break
        }
        return FromImportStmt(sb.toString(), names, star = false).also { it.line = l }
    }

    private val augOps = setOf("+=", "-=", "*=", "/=", "//=", "%=", "**=")

    private fun parseExprOrAssign(l: Int): Stmt {
        val first = parseExprList()
        val targets = if (first is TupleExpr) first.items.toList() else listOf(first)

        if (at(TT.OP) && peek().value in augOps) {
            val op = next().value
            validateTarget(first)
            val value = parseExpr()
            return AugAssignStmt(first, op, value).also { it.line = l }
        }

        if (acceptOp("=")) {
            val all = ArrayList(targets)
            var value = parseExprList()
            while (acceptOp("=")) {
                validateTarget(value)
                all.add(value)
                value = parseExprList()
            }
            all.forEach { validateTarget(it) }
            return AssignStmt(all, value).also { it.line = l }
        }
        return ExprStmt(first).also { it.line = l }
    }

    private fun validateTarget(t: Expr) {
        when (t) {
            is NameExpr, is IndexExpr, is AttrExpr -> Unit
            is TupleExpr -> t.items.forEach { validateTarget(it) }
            else -> err("нельзя присваивать в это выражение")
        }
    }

    /** Выражение или кортеж через запятую (для присваивания и for-цели). */
    private fun parseExprList(): Expr {
        val first = parseExpr()
        if (!atOp(",")) return first
        val items = ArrayList<Expr>()
        items.add(first)
        while (acceptOp(",")) {
            if (at(TT.NEWLINE) || at(TT.EOF) || atOp("=") || atOp(")") || atOp("]")) break
            items.add(parseExpr())
        }
        return TupleExpr(items).withLine()
    }

    // ----------------------------------------------------------- выражения

    fun parseExpr(): Expr = parseOr()

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (atKw("or")) {
            next()
            val right = parseAnd()
            left = BoolExpr("or", left, right).withLine()
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (atKw("and")) {
            next()
            val right = parseNot()
            left = BoolExpr("and", left, right).withLine()
        }
        return left
    }

    private fun parseNot(): Expr {
        if (atKw("not")) {
            val l = line()
            next()
            return UnaryExpr("not", parseNot()).also { it.line = l }
        }
        return parseComparison()
    }

    private val cmpOps = setOf("==", "!=", "<", ">", "<=", ">=")

    private fun parseComparison(): Expr {
        val left = parseSum()
        val ops = ArrayList<String>()
        val rights = ArrayList<Expr>()
        while (true) {
            when {
                at(TT.OP) && peek().value in cmpOps -> {
                    ops.add(next().value)
                    rights.add(parseSum())
                }
                atKw("in") -> {
                    next()
                    ops.add("in")
                    rights.add(parseSum())
                }
                atKw("not") && peek(1).type == TT.KEYWORD && peek(1).value == "in" -> {
                    next(); next()
                    ops.add("not in")
                    rights.add(parseSum())
                }
                atKw("is") -> {
                    next()
                    if (acceptKw("not")) {
                        ops.add("is not")
                    } else {
                        ops.add("is")
                    }
                    rights.add(parseSum())
                }
                else -> break
            }
        }
        if (ops.isEmpty()) return left
        return CompareExpr(left, ops, rights).withLine()
    }

    private fun parseSum(): Expr {
        var left = parseTerm()
        while (at(TT.OP) && (peek().value == "+" || peek().value == "-")) {
            val op = next().value
            val right = parseTerm()
            left = BinOpExpr(op, left, right).withLine()
        }
        return left
    }

    private fun parseTerm(): Expr {
        var left = parseUnary()
        while (at(TT.OP) && peek().value in setOf("*", "/", "//", "%")) {
            val op = next().value
            val right = parseUnary()
            left = BinOpExpr(op, left, right).withLine()
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (at(TT.OP) && (peek().value == "-" || peek().value == "+")) {
            val l = line()
            val op = next().value
            return UnaryExpr(op, parsePower()).also { it.line = l }
        }
        return parsePower()
    }

    private fun parsePower(): Expr {
        val left = parsePostfix()
        if (atOp("**")) {
            val l = line()
            next()
            val right = parseUnary()
            return BinOpExpr("**", left, right).also { it.line = l }
        }
        return left
    }

    private fun parsePostfix(): Expr {
        var e = parseAtom()
        while (true) {
            when {
                atOp("(") -> e = parseCall(e)
                atOp("[") -> e = parseIndex(e)
                atOp(".") -> {
                    val l = line()
                    next()
                    val name = expect(TT.NAME, null, "имя атрибута").value
                    e = AttrExpr(e, name).also { it.line = l }
                }
                else -> return e
            }
        }
    }

    private fun parseCall(fn: Expr): Expr {
        val l = line()
        next() // (
        val args = ArrayList<Expr>()
        val kwargs = ArrayList<Pair<String, Expr>>()
        if (!atOp(")")) {
            while (true) {
                if (at(TT.NAME) && peek(1).type == TT.OP && peek(1).value == "=") {
                    val name = next().value
                    next() // =
                    val v = parseExpr()
                    kwargs.add(name to v)
                } else {
                    args.add(parseExpr())
                }
                if (!acceptOp(",")) break
                if (atOp(")")) break
            }
        }
        expect(TT.OP, ")", "')'")
        return CallExpr(fn, args, kwargs).also { it.line = l }
    }

    private fun parseIndex(obj: Expr): Expr {
        val l = line()
        next() // [
        if (atOp(":")) {
            next()
            val upper = slicePart()
            val step = if (acceptOp(":")) slicePart() else null
            expect(TT.OP, "]", "']'")
            return IndexExpr(obj, SliceExpr(null, upper, step)).also { it.line = l }
        }
        val first = parseExpr()
        if (atOp(":")) {
            next()
            val upper = slicePart()
            val step = if (acceptOp(":")) slicePart() else null
            expect(TT.OP, "]", "']'")
            return IndexExpr(obj, SliceExpr(first, upper, step)).also { it.line = l }
        }
        expect(TT.OP, "]", "']'")
        return IndexExpr(obj, first).also { it.line = l }
    }

    private fun slicePart(): Expr? {
        if (atOp(":") || atOp("]")) return null
        return parseExpr()
    }

    private fun parseAtom(): Expr {
        val l = line()
        when {
            at(TT.NUMBER) -> {
                val text = next().value
                if (text.contains('.') || text.contains('e', ignoreCase = true)) {
                    val d = text.toDoubleOrNull() ?: err("неверное число $text")
                    return LitExpr(PyFloat(d)).also { it.line = l }
                }
                val v = text.toLongOrNull() ?: err("неверное число $text")
                return LitExpr(PyInt(v)).also { it.line = l }
            }
            at(TT.STRING) -> return parseStrings(l)
            at(TT.NAME) -> {
                val name = next().value
                return NameExpr(name).also { it.line = l }
            }
            atKw("True") -> { next(); return LitExpr(PyBool(true)).also { it.line = l } }
            atKw("False") -> { next(); return LitExpr(PyBool(false)).also { it.line = l } }
            atKw("None") -> { next(); return LitExpr(PyNone).also { it.line = l } }
            atKw("lambda") -> err("лямбда-функции не поддерживаются")
            atOp("(") -> {
                next()
                if (atOp(")")) err("пустые скобки")
                val e = parseExprList()
                expect(TT.OP, ")", "')'")
                return e
            }
            atOp("[") -> return parseListLiteral(l)
            atOp("{") -> return parseDictLiteral(l)
            at(TT.KEYWORD) -> err("«${peek().value}» нельзя использовать в выражении")
            else -> err("неожиданный символ «${peek().value}»")
        }
    }

    private fun parseStrings(l: Int): Expr {
        var plain = StringBuilder()
        var fParts: ArrayList<FPart>? = null

        while (at(TT.STRING)) {
            val t = next()
            if ('f' in t.strPrefix) {
                val f = fParts ?: ArrayList<FPart>().also { fParts = it }
                if (plain.isNotEmpty()) {
                    f.add(FPart(plain.toString(), null, null))
                    plain = StringBuilder()
                }
                f.addAll(parseFStringParts(t.value, l))
            } else {
                plain.append(t.value)
            }
        }
        val f = fParts
        return if (f == null) {
            LitExpr(PyStr(plain.toString())).also { it.line = l }
        } else {
            if (plain.isNotEmpty()) f.add(FPart(plain.toString(), null, null))
            FStringExpr(f).also { it.line = l }
        }
    }

    private fun parseFStringParts(text: String, l: Int): List<FPart> {
        val parts = ArrayList<FPart>()
        val lit = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' && i + 1 < text.length && text[i + 1] == '{') {
                lit.append('{'); i += 2; continue
            }
            if (c == '}' && i + 1 < text.length && text[i + 1] == '}') {
                lit.append('}'); i += 2; continue
            }
            if (c == '{') {
                var depth = 0
                var j = i + 1
                var inQuote: Char? = null
                while (j < text.length) {
                    val ch = text[j]
                    if (inQuote != null) {
                        if (ch == inQuote) inQuote = null
                        j++
                        continue
                    }
                    when (ch) {
                        '\'', '"' -> inQuote = ch
                        '{' -> depth++
                        '}' -> if (depth == 0) break else depth--
                    }
                    j++
                }
                if (j >= text.length) throw PyError(l, "незакрытая { } в f-строке")
                val inner = text.substring(i + 1, j)
                val colon = findTopLevel(inner, ':')
                var exprText: String
                val spec: String?
                if (colon >= 0) {
                    exprText = inner.substring(0, colon)
                    spec = inner.substring(colon + 1)
                } else {
                    exprText = inner
                    spec = null
                }
                // конверсия !r / !s / !a — только в самой части-выражении
                val bang = findTopLevel(exprText, '!')
                if (bang >= 0) exprText = exprText.substring(0, bang)
                if (exprText.isBlank()) throw PyError(l, "пустые { } в f-строке")
                val subTokens = PyLexer(exprText).tokenize()
                val e = PyParser(subTokens, lineShift = l - 1).parseSingleExpr()
                parts.add(FPart(lit.toString(), e, spec))
                lit.setLength(0)
                i = j + 1
            } else {
                lit.append(c)
                i++
            }
        }
        parts.add(FPart(lit.toString(), null, null))
        return parts
    }

    private fun findTopLevel(s: String, target: Char): Int {
        var depth = 0
        var inQuote: Char? = null
        for (idx in s.indices) {
            val ch = s[idx]
            if (inQuote != null) {
                if (ch == inQuote) inQuote = null
                continue
            }
            when (ch) {
                '\'', '"' -> inQuote = ch
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                else -> if (ch == target && depth == 0) return idx
            }
        }
        return -1
    }

    private fun parseListLiteral(l: Int): Expr {
        next() // [
        if (atOp("]")) {
            next()
            return ListExpr(emptyList()).also { it.line = l }
        }
        val first = parseExpr()
        if (atKw("for")) {
            // выражение-список: [expr for x in iter (if cond)?]
            next()
            val target = expect(TT.NAME, null, "имя переменной").value
            expect(TT.KEYWORD, "in", "'in'")
            val iter = parseExpr()
            var cond: Expr? = null
            if (acceptKw("if")) cond = parseExpr()
            expect(TT.OP, "]", "']'")
            return ListCompExpr(first, target, iter, cond).also { it.line = l }
        }
        val items = ArrayList<Expr>()
        items.add(first)
        while (acceptOp(",")) {
            if (atOp("]")) break
            items.add(parseExpr())
        }
        expect(TT.OP, "]", "']'")
        return ListExpr(items).also { it.line = l }
    }

    private fun parseDictLiteral(l: Int): Expr {
        next() // {
        if (atOp("}")) {
            next()
            return DictExpr(emptyList(), emptyList()).also { it.line = l }
        }
        val keys = ArrayList<Expr>()
        val values = ArrayList<Expr>()
        while (true) {
            val k = parseExpr()
            expect(TT.OP, ":", "':'")
            val v = parseExpr()
            keys.add(k)
            values.add(v)
            if (!acceptOp(",")) break
            if (atOp("}")) break
        }
        expect(TT.OP, "}", "'}'")
        return DictExpr(keys, values).also { it.line = l }
    }
}
