package com.htmleditor.app.py

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Дерево-выполняющий интерпретатор Python (офлайн, встроен в приложение).
 * Поддерживает основной синтаксис школьной программы + turtle/print/input.
 */
class PyInterpreter(private val host: ExecHost) {

    // ------------------------------------------------------------ сигналы

    private class BreakSig : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class ContinueSig : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class ReturnSig(val v: PyVal) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    // --------------------------------------------------------------- state

    private val globals = Env(null)
    private var currentLine = 1
    private val turtle = TurtleSim()
    private val bound = HashMap<String, PyBuiltin>()

    private fun err(msg: String, line: Int = currentLine): Nothing =
        throw PyError(line, msg)

    fun run(code: String) {
        registerBuiltins()
        val stmts = PyParser(PyLexer(code).tokenize()).parseModule()
        try {
            execList(stmts, globals)
        } catch (e: ReturnSig) {
            throw PyError(currentLine, "return вне функции")
        } catch (e: BreakSig) {
            throw PyError(currentLine, "break вне цикла")
        } catch (e: ContinueSig) {
            throw PyError(currentLine, "continue вне цикла")
        } catch (e: StackOverflowError) {
            throw PyError(currentLine, "слишком глубокая рекурсия")
        }
    }

    // ------------------------------------------------------- выполнение

    private fun execList(list: List<Stmt>, env: Env) {
        for (s in list) {
            currentLine = s.line
            host.tick()
            exec(s, env)
        }
    }

    private fun exec(s: Stmt, env: Env) {
        when (s) {
            is ExprStmt -> eval(s.e, env)

            is AssignStmt -> {
                val v = eval(s.value, env)
                for (t in s.targets) assignTo(t, v, env)
            }

            is AugAssignStmt -> {
                val cur = eval(s.target, env)
                val rhs = eval(s.value, env)
                val res = binop(s.op.removeSuffix("="), cur, rhs, s.line)
                assignTo(s.target, res, env)
            }

            is IfStmt -> {
                when {
                    eval(s.cond, env).truthy() -> execList(s.thenBody, env)
                    else -> {
                        var done = false
                        for ((c, b) in s.elifs) {
                            if (eval(c, env).truthy()) {
                                execList(b, env)
                                done = true
                                break
                            }
                        }
                        if (!done && s.elseBody != null) execList(s.elseBody, env)
                    }
                }
            }

            is WhileStmt -> {
                while (eval(s.cond, env).truthy()) {
                    host.tick()
                    try {
                        execList(s.body, env)
                    } catch (e: ContinueSig) {
                        // следующая итерация
                    } catch (e: BreakSig) {
                        break
                    }
                }
            }

            is ForStmt -> {
                val iterable = eval(s.iter, env)
                for (item in iterSeq(iterable, s.line)) {
                    host.tick()
                    assignTo(s.target, item, env)
                    try {
                        execList(s.body, env)
                    } catch (e: ContinueSig) {
                        // следующая итерация
                    } catch (e: BreakSig) {
                        break
                    }
                }
            }

            is FuncStmt -> {
                val params = s.params.map { p ->
                    PyParam(p.name, p.default?.let { eval(it, env) })
                }
                env.set(s.name, PyFunc(s.name, params, s.body, env))
            }

            is ReturnStmt -> throw ReturnSig(s.value?.let { eval(it, env) } ?: PyNone)
            is BreakStmt -> throw BreakSig()
            is ContinueStmt -> throw ContinueSig()
            is PassStmt -> Unit

            is ImportStmt -> {
                val first = s.name.substringBefore('.')
                val mod = modules[first] ?: err("модуль '${first}' не найден (доступны: math, random, turtle)")
                env.set(s.alias ?: first, mod)
            }

            is FromImportStmt -> {
                val mod = modules[s.module] ?: err("модуль '${s.module}' не найден (доступны: math, random, turtle)")
                if (s.star) {
                    for ((k, v) in mod.attrs) env.set(k, v)
                } else {
                    for ((n, alias) in s.names) {
                        val obj = mod.attrs[n] ?: err("в модуле нет объекта '${n}'")
                        env.set(alias ?: n, obj)
                    }
                }
            }
        }
    }

    private fun assignTo(target: Expr, v: PyVal, env: Env) {
        when (target) {
            is NameExpr -> env.set(target.name, v)

            is IndexExpr -> {
                val obj = eval(target.obj, env)
                val idx = eval(target.index, env)
                when (obj) {
                    is PyList -> {
                        val i = indexInt(idx, obj.items.size)
                        obj.items[i] = v
                    }
                    is PyDict -> {
                        obj.entries[keyOf(idx, target.line)] = v
                    }
                    else -> err("объект типа '${pyTypeName(obj)}' нельзя индексировать")
                }
            }

            is AttrExpr -> err("нельзя присваивать в атрибут")

            is TupleExpr -> {
                val items = when (v) {
                    is PyList -> v.items
                    is PyTuple -> v.items
                    is PyStr -> v.v.map { PyStr(it.toString()) }
                    else -> err("нельзя распаковать '${pyTypeName(v)}'")
                }
                if (items.size != target.items.size) {
                    err(
                        if (items.size < target.items.size) "не хватает элементов для распаковки"
                        else "слишком много элементов для распаковки"
                    )
                }
                for (i in target.items.indices) assignTo(target.items[i], items[i], env)
            }

            else -> err("нельзя присваивать в это выражение")
        }
    }

    /** Индекс с поддержкой отрицательных и проверкой границ. */
    private fun indexInt(idx: PyVal, size: Int): Int {
        val raw = asLong(idx) ?: err("индекс должен быть числом")
        val i = if (raw < 0) raw + size else raw
        if (i < 0 || i >= size) err("индекс выходит за границы (длина $size)")
        return i.toInt()
    }

    private fun keyOf(v: PyVal, line: Int): Any = try {
        dictKey(v)
    } catch (e: Exception) {
        throw PyError(line, "неподдерживаемый тип ключа словаря: '${pyTypeName(v)}'")
    }

    // ------------------------------------------------------- вычисления

    private fun eval(e: Expr, env: Env): PyVal {
        currentLine = e.line
        return when (e) {
            is LitExpr -> e.v

            is NameExpr -> env.get(e.name)
                ?: err("имя '${e.name}' не определено")

            is TupleExpr -> PyTuple(e.items.map { eval(it, env) })

            is ListExpr -> PyList(e.items.mapTo(ArrayList()) { eval(it, env) })

            is DictExpr -> {
                val d = PyDict(LinkedHashMap())
                for (i in e.keys.indices) {
                    val k = eval(e.keys[i], env)
                    d.entries[keyOf(k, e.line)] = eval(e.values[i], env)
                }
                d
            }

            is ListCompExpr -> {
                val out = ArrayList<PyVal>()
                val it0 = iterSeq(eval(e.iter, env), e.line)
                for (item in it0) {
                    currentLine = e.line
                    val inner = Env(env)
                    inner.set(e.target, item)
                    if (e.cond == null || eval(e.cond, inner).truthy()) {
                        out.add(eval(e.result, inner))
                    }
                }
                PyList(out)
            }

            is BinOpExpr -> binop(e.op, eval(e.left, env), eval(e.right, env), e.line)

            is UnaryExpr -> {
                val v = eval(e.operand, env)
                when (e.op) {
                    "-" -> when (v) {
                        is PyInt -> PyInt(-v.v)
                        is PyFloat -> PyFloat(-v.v)
                        is PyBool -> PyInt(if (v.v) -1L else 1L)
                        else -> err("унарный минус не для числа")
                    }
                    "+" -> when (v) {
                        is PyInt, is PyFloat, is PyBool -> v
                        else -> err("унарный плюс не для числа")
                    }
                    "not" -> PyBool(!v.truthy())
                    else -> err("неизвестный унарный оператор ${e.op}")
                }
            }

            is CompareExpr -> {
                var left = eval(e.left, env)
                for (i in e.ops.indices) {
                    val right = eval(e.rights[i], env)
                    val ok = compare(e.ops[i], left, right, e.line)
                    if (!ok) return PyBool(false)
                    left = right
                }
                PyBool(true)
            }

            is BoolExpr -> {
                val l = eval(e.left, env)
                when (e.op) {
                    "and" -> if (!l.truthy()) l else eval(e.right, env)
                    "or" -> if (l.truthy()) l else eval(e.right, env)
                    else -> err("неизвестный оператор ${e.op}")
                }
            }

            is CallExpr -> evalCall(e, env)

            is IndexExpr -> {
                val obj = eval(e.obj, env)
                val idx = e.index
                if (idx is SliceExpr) sliceGet(obj, idx, env, e.line)
                else indexGet(obj, eval(idx, env), e.line)
            }

            is SliceExpr -> err("срез можно использовать только в индексе [ ]")

            is AttrExpr -> attrGet(eval(e.obj, env), e.name, e.line)

            is FStringExpr -> {
                val sb = StringBuilder()
                for (p in e.parts) {
                    if (p.expr == null) {
                        sb.append(p.text)
                    } else {
                        val v = eval(p.expr, env)
                        sb.append(formatSpec(v, p.spec))
                    }
                }
                PyStr(sb.toString())
            }
        }
    }

    private fun formatSpec(v: PyVal, spec: String?): PyStr {
        if (spec.isNullOrEmpty()) return PyStr(pyStr(v))
        // поддерживаем .Nf, d, s
        val f = Regex("^(\\.\\d+)f$").find(spec)
        if (f != null) {
            val digits = f.groupValues[1].removePrefix(".").toInt()
            val x = asFloat(v) ?: return PyStr(pyStr(v))
            return PyStr(String.format(java.util.Locale.US, "%.${digits}f", x))
        }
        if (spec == "d") {
            val l = asLong(v) ?: return PyStr(pyStr(v))
            return PyStr(l.toString())
        }
        if (spec == "s" || spec.startsWith(".") || spec == "g") return PyStr(pyStr(v))
        return PyStr(pyStr(v))
    }

    // --------------------------------------------------- вызовы функций

    private fun evalCall(e: CallExpr, env: Env): PyVal {
        val args = e.args.map { eval(it, env) }
        val kwargs = LinkedHashMap<String, PyVal>()
        for ((k, v) in e.kwargs) kwargs[k] = eval(v, env)
        val line = e.line

        if (e.fn is AttrExpr) {
            val fn = e.fn as AttrExpr
            val obj = eval(fn.obj, env)
            val method = methodOf(obj, fn.name, args, kwargs, line)
            if (method != null) return method
            val target = attrGet(obj, fn.name, line)
            return invoke(target, args, kwargs, line)
        }

        val target = eval(e.fn, env)
        return invoke(target, args, kwargs, line)
    }

    private fun invoke(target: PyVal, args: List<PyVal>, kwargs: Map<String, PyVal>, line: Int): PyVal {
        return when (target) {
            is PyBuiltin -> {
                currentLine = line
                try {
                    target.fn(args, kwargs)
                } catch (e: PyError) {
                    throw if (e.line > 0) e else PyError(line, e.message ?: "ошибка")
                }
            }
            is PyFunc -> callFunc(target, args, kwargs, line)
            else -> err("'${pyTypeName(target)}' не является функцией", line)
        }
    }

    private fun callFunc(f: PyFunc, args: List<PyVal>, kwargs: Map<String, PyVal>, line: Int): PyVal {
        val env = Env(f.scope ?: globals)
        val boundNames = HashSet<String>()

        for (i in args.indices) {
            if (i >= f.params.size) err("слишком много аргументов для '${f.name}'", line)
            val p = f.params[i]
            if (p.name in boundNames) err("аргумент '${p.name}' передан дважды", line)
            env.set(p.name, args[i])
            boundNames.add(p.name)
        }
        for ((k, v) in kwargs) {
            if (f.params.none { it.name == k }) err("неизвестный аргумент '${k}'", line)
            if (k in boundNames) err("аргумент '${k}' передан дважды", line)
            env.set(k, v)
            boundNames.add(k)
        }
        for (p in f.params) {
            if (p.name in boundNames) continue
            if (p.default != null) env.set(p.name, p.default)
            else err("не передан обязательный аргумент '${p.name}'", line)
        }

        return try {
            execList(f.body, env)
            PyNone
        } catch (r: ReturnSig) {
            r.v
        }
    }

    // ------------------------------------------------ операции и сравнения

    private fun asLong(v: PyVal): Long? = when (v) {
        is PyInt -> v.v
        is PyBool -> if (v.v) 1L else 0L
        else -> null
    }

    private fun asFloatOrNull(v: PyVal): Double? = when (v) {
        is PyInt -> v.v.toDouble()
        is PyFloat -> v.v
        is PyBool -> if (v.v) 1.0 else 0.0
        else -> null
    }

    private fun asFloat(v: PyVal): Double? = asFloatOrNull(v)

    private fun binop(op: String, a: PyVal, b: PyVal, line: Int): PyVal {
        when (op) {
            "+" -> {
                if (a is PyStr && b is PyStr) return PyStr(a.v + b.v)
                if (a is PyList && b is PyList) return PyList(ArrayList(a.items + b.items))
                if (a is PyTuple && b is PyTuple) return PyTuple(a.items + b.items)
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null) return PyInt(la + lb)
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) return PyFloat(fa + fb)
                err("нельзя сложить '${pyTypeName(a)}' и '${pyTypeName(b)}'", line)
            }
            "-" -> {
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null) return PyInt(la - lb)
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) return PyFloat(fa - fb)
                err("нельзя вычесть '${pyTypeName(a)}' и '${pyTypeName(b)}'", line)
            }
            "*" -> {
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null) return PyInt(la * lb)
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) return PyFloat(fa * fb)
                if (a is PyStr && lb != null) return PyStr(repeatSafe(a.v, lb, line))
                if (b is PyStr && la != null) return PyStr(repeatSafe(b.v, la, line))
                if (a is PyList && lb != null) {
                    if (lb > 1_000_000) err("слишком большой список", line)
                    val out = ArrayList<PyVal>()
                    repeat(lb.toInt()) { out.addAll(a.items) }
                    return PyList(out)
                }
                if (b is PyList && la != null) {
                    if (la > 1_000_000) err("слишком большой список", line)
                    val out = ArrayList<PyVal>()
                    repeat(la.toInt()) { out.addAll(b.items) }
                    return PyList(out)
                }
                err("нельзя умножить '${pyTypeName(a)}' и '${pyTypeName(b)}'", line)
            }
            "/" -> {
                val fa = asFloatOrNull(a) ?: err("нельзя разделить '${pyTypeName(a)}'", line)
                val fb = asFloatOrNull(b) ?: err("нельзя разделить на '${pyTypeName(b)}'", line)
                if (fb == 0.0) err("деление на ноль", line)
                return PyFloat(fa / fb)
            }
            "//" -> {
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null) {
                    if (lb == 0L) err("деление на ноль", line)
                    return PyInt(Math.floorDiv(la, lb))
                }
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) {
                    if (fb == 0.0) err("деление на ноль", line)
                    return PyFloat(floor(fa / fb))
                }
                err("нельзя разделить '${pyTypeName(a)}'", line)
            }
            "%" -> {
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null) {
                    if (lb == 0L) err("деление на ноль", line)
                    return PyInt(Math.floorMod(la, lb))
                }
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) {
                    if (fb == 0.0) err("деление на ноль", line)
                    var r = fa % fb
                    if (r != 0.0 && (r < 0) != (fb < 0)) r += fb
                    return PyFloat(r)
                }
                err("нельзя взять остаток от '${pyTypeName(a)}'", line)
            }
            "**" -> {
                val la = asLong(a); val lb = asLong(b)
                if (la != null && lb != null && lb >= 0 && lb < 64) {
                    var r = 1.0
                    for (i in 0 until lb) r *= la
                    return if (abs(r) <= 9.007199254740992E15) PyInt(r.toLong()) else PyFloat(r)
                }
                val fa = asFloatOrNull(a); val fb = asFloatOrNull(b)
                if (fa != null && fb != null) return PyFloat(Math.pow(fa, fb))
                err("нельзя возвести '${pyTypeName(a)}' в степень", line)
            }
            else -> err("неизвестный оператор ${op}", line)
        }
    }

    private fun repeatSafe(s: String, n: Long, line: Int): String {
        if (n <= 0) return ""
        if (n > 10_000_000) err("слишком длинная строка", line)
        return s.repeat(n.toInt())
    }

    private fun contains(container: PyVal, item: PyVal, line: Int): Boolean = when (container) {
        is PyStr -> {
            if (item !is PyStr) err("в строке можно искать только подстроку")
            container.v.contains(item.v)
        }
        is PyList -> container.items.any { pyEquals(it, item) }
        is PyTuple -> container.items.any { pyEquals(it, item) }
        is PyDict -> {
            val k = try {
                dictKey(item)
            } catch (e: Exception) {
                throw PyError(line, "неподдерживаемый тип ключа")
            }
            container.entries.containsKey(k)
        }
        is PyRange -> {
            val x = asLong(item)
            if (x == null) false else {
                val (lo, hi) = if (container.step > 0)
                    container.start until container.stop
                else (container.stop + 1)..container.start
                x in lo..hi
            }
        }
        else -> err("объект типа '${pyTypeName(container)}' не поддерживает 'in'", line)
    }

    private fun compare(op: String, a: PyVal, b: PyVal, line: Int): Boolean = when (op) {
        "==" -> pyEquals(a, b)
        "!=" -> !pyEquals(a, b)
        "is" -> if (a is PyList || a is PyDict || a is PyFunc || b is PyList || b is PyDict || b is PyFunc)
            a === b else pyEquals(a, b)
        "is not" -> if (a is PyList || a is PyDict || a is PyFunc || b is PyList || b is PyDict || b is PyFunc)
            a !== b else !pyEquals(a, b)
        "in" -> contains(b, a, line)
        "not in" -> !contains(b, a, line)
        "<", ">", "<=", ">=" -> {
            val c = compareVals(a, b, line)
            when (op) {
                "<" -> c < 0
                ">" -> c > 0
                "<=" -> c <= 0
                else -> c >= 0
            }
        }
        else -> err("неизвестный оператор сравнения ${op}", line)
    }

    private fun compareVals(a: PyVal, b: PyVal, line: Int): Int = when {
        (a is PyInt || a is PyFloat || a is PyBool) && (b is PyInt || b is PyFloat || b is PyBool) ->
            asFloatOrNull(a)!!.compareTo(asFloatOrNull(b)!!)
        a is PyStr && b is PyStr -> a.v.compareTo(b.v)
        a is PyBool && b is PyBool -> a.v.compareTo(b.v)
        a === PyNone && b === PyNone -> 0
        else -> err("нельзя сравнить '${pyTypeName(a)}' и '${pyTypeName(b)}'", line)
    }

    // ---------------------------------------------------- индексы/атрибуты

    private fun indexGet(obj: PyVal, idx: PyVal, line: Int): PyVal {
        return when (obj) {
            is PyList -> obj.items[indexInt(idx, obj.items.size)]
            is PyTuple -> obj.items[indexInt(idx, obj.items.size)]
            is PyStr -> {
                val i = indexInt(idx, obj.v.length)
                PyStr(obj.v[i].toString())
            }
            is PyDict -> {
                val k = keyOf(idx, line)
                obj.entries[k] ?: err("ключ ${pyRepr(idx)} не найден", line)
            }
            is PyRange -> {
                val i = asLong(idx) ?: err("индекс должен быть числом", line)
                val n = obj.length()
                val j = if (i < 0) i + n else i
                if (j < 0 || j >= n) err("индекс выходит за границы", line)
                PyInt(obj.at(j))
            }
            else -> err("объект типа '${pyTypeName(obj)}' нельзя индексировать", line)
        }
    }

    private fun sliceGet(obj: PyVal, s: SliceExpr, env: Env, line: Int): PyVal {
        val size = when (obj) {
            is PyList -> obj.items.size
            is PyTuple -> obj.items.size
            is PyStr -> obj.v.length
            else -> err("объект типа '${pyTypeName(obj)}' не поддерживает срезы", line)
        }
        val step = s.step?.let { asLong(eval(it, env)) ?: err("шаг среза должен быть числом", line) } ?: 1L
        if (step == 0L) err("шаг среза не может быть нулём", line)
        val lowerL = s.lower?.let { asLong(eval(it, env)) }
        val upperL = s.upper?.let { asLong(eval(it, env)) }

        val indices = ArrayList<Int>()
        if (step > 0) {
            var start = lowerL ?: 0L
            var stop = upperL ?: size.toLong()
            if (start < 0) start += size
            if (stop < 0) stop += size
            start = start.coerceIn(0, size.toLong())
            stop = stop.coerceIn(0, size.toLong())
            var i = start
            while (i < stop) {
                indices.add(i.toInt())
                i += step
            }
        } else {
            var start = lowerL ?: (size - 1).toLong()
            var stop = upperL ?: -1L
            if (start < 0 && lowerL != null) start += size
            if (stop < 0 && upperL != null && stop != -1L) stop += size
            start = start.coerceIn(-1, (size - 1).toLong())
            stop = stop.coerceIn(-1, (size - 1).toLong())
            var i = start
            while (i > stop) {
                if (i >= 0) indices.add(i.toInt())
                i += step
            }
        }

        return when (obj) {
            is PyStr -> PyStr(indices.joinToString("") { obj.v[it].toString() })
            is PyList -> PyList(indices.mapTo(ArrayList()) { obj.items[it] })
            is PyTuple -> PyTuple(indices.map { obj.items[it] })
            else -> err("неподдерживаемый срез", line)
        }
    }

    private fun attrGet(obj: PyVal, name: String, line: Int): PyVal = when (obj) {
        is PyModule -> obj.attrs[name] ?: err("в модуле нет объекта '${name}'", line)
        else -> err("у '${pyTypeName(obj)}' нет атрибутов", line)
    }

    // ------------------------------------------------- итерирование

    private fun iterSeq(v: PyVal, line: Int): Sequence<PyVal> = when (v) {
        is PyRange -> sequence {
            var i = 0L
            val n = v.length()
            while (i < n) {
                yield(PyInt(v.at(i)))
                i++
            }
        }
        is PyList -> v.items.asSequence()
        is PyTuple -> v.items.asSequence()
        is PyStr -> v.v.asSequence().map { PyStr(it.toString()) }
        is PyDict -> v.entries.keys.asSequence().map {
            when (it) {
                is String -> PyStr(it)
                is Long -> PyInt(it)
                is Boolean -> PyBool(it)
                is Double -> PyFloat(it)
                else -> PyStr(it.toString())
            }
        }
        else -> err("объект типа '${pyTypeName(v)}' не является итерируемым", line)
    }

    // ------------------------------------- методы встроенных типов (str/list/dict)

    private fun methodOf(
        obj: PyVal,
        name: String,
        args: List<PyVal>,
        kwargs: Map<String, PyVal>,
        line: Int
    ): PyVal? {
        when (obj) {
            is PyStr -> return when (name) {
                "upper" -> PyStr(obj.v.uppercase())
                "lower" -> PyStr(obj.v.lowercase())
                "strip" -> PyStr(obj.v.trim())
                "split" -> {
                    val sep = args.getOrNull(0)
                    when {
                        sep == null && kwargs.isEmpty() ->
                            PyList(obj.v.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapTo(ArrayList()) { PyStr(it) })
                        sep is PyStr -> {
                            if (sep.v.isEmpty()) {
                                PyList(obj.v.map { PyStr(it.toString()) })
                            } else {
                                PyList(obj.v.split(sep.v).mapTo(ArrayList()) { PyStr(it) })
                            }
                        }
                        else -> null
                    }
                }
                "join" -> {
                    val it = args.getOrNull(0)
                    when (it) {
                        is PyList -> PyStr(it.items.joinToString("") { pyStr(it) })
                        is PyTuple -> PyStr(it.items.joinToString("") { pyStr(it) })
                        else -> null
                    }
                }
                "replace" -> {
                    val a = args.getOrNull(0); val b = args.getOrNull(1)
                    if (a is PyStr && b is PyStr) PyStr(obj.v.replace(a.v, b.v)) else null
                }
                "find" -> {
                    val a = args.getOrNull(0)
                    if (a is PyStr) PyInt(obj.v.indexOf(a.v).toLong()) else null
                }
                "startswith" -> {
                    val a = args.getOrNull(0)
                    if (a is PyStr) PyBool(obj.v.startsWith(a.v)) else null
                }
                "endswith" -> {
                    val a = args.getOrNull(0)
                    if (a is PyStr) PyBool(obj.v.endsWith(a.v)) else null
                }
                else -> null
            }

            is PyList -> return when (name) {
                "append" -> {
                    val a = args.getOrNull(0) ?: return null
                    obj.items.add(a)
                    PyNone
                }
                "insert" -> {
                    val i = args.getOrNull(0); val a = args.getOrNull(1)
                    if (i == null || a == null) return null
                    val idx = (asLong(i) ?: return null).coerceIn(0, obj.items.size.toLong()).toInt()
                    obj.items.add(idx, a)
                    PyNone
                }
                "remove" -> {
                    val a = args.getOrNull(0) ?: return null
                    val pos = obj.items.indexOfFirst { pyEquals(it, a) }
                    if (pos >= 0) obj.items.removeAt(pos)
                    PyNone
                }
                "pop" -> {
                    if (args.isEmpty()) {
                        if (obj.items.isEmpty()) throw PyError(line, "список пуст")
                        obj.items.removeAt(obj.items.size - 1)
                    } else {
                        val i = asLong(args[0]) ?: return null
                        val idx = if (i < 0) i + obj.items.size else i
                        if (idx < 0 || idx >= obj.items.size) throw PyError(line, "индекс вне диапазона")
                        obj.items.removeAt(idx.toInt())
                    }
                }
                "extend" -> {
                    val it = args.getOrNull(0)
                    when (it) {
                        is PyList -> obj.items.addAll(it.items)
                        is PyTuple -> obj.items.addAll(it.items)
                        is PyStr -> it.v.forEach { c -> obj.items.add(PyStr(c.toString())) }
                        else -> return null
                    }
                    PyNone
                }
                "reverse" -> {
                    obj.items.reverse()
                    PyNone
                }
                "sort" -> {
                    val sorted = obj.items.sortedWith { x, y -> compareVals(x, y, line) }
                    obj.items.clear()
                    obj.items.addAll(sorted)
                    PyNone
                }
                "clear" -> {
                    obj.items.clear()
                    PyNone
                }
                else -> null
            }

            is PyDict -> return when (name) {
                "get" -> {
                    val k = args.getOrNull(0) ?: return null
                    val key = try {
                        dictKey(k)
                    } catch (e: Exception) {
                        throw PyError(line, "неподдерживаемый тип ключа")
                    }
                    obj.entries[key] ?: args.getOrNull(1) ?: PyNone
                }
                "keys" -> PyList(obj.entries.keys.mapTo(ArrayList()) {
                    when (it) {
                        is String -> PyStr(it)
                        is Long -> PyInt(it)
                        is Boolean -> PyBool(it)
                        is Double -> PyFloat(it)
                        else -> PyStr(it.toString())
                    }
                })
                "values" -> PyList(obj.entries.values.mapTo(ArrayList()) { it })
                "items" -> PyList(obj.entries.entries.mapTo(ArrayList()) { (k, v) ->
                    PyTuple(
                        listOf(
                            when (k) {
                                is String -> PyStr(k)
                                is Long -> PyInt(k)
                                is Boolean -> PyBool(k)
                                is Double -> PyFloat(k)
                                else -> PyStr(k.toString())
                            },
                            v
                        )
                    )
                })
                "pop" -> {
                    val k = args.getOrNull(0) ?: return null
                    val key = try {
                        dictKey(k)
                    } catch (e: Exception) {
                        throw PyError(line, "неподдерживаемый тип ключа")
                    }
                    obj.entries.remove(key) ?: PyNone
                }
                "clear" -> {
                    obj.entries.clear()
                    PyNone
                }
                else -> null
            }
            else -> {
                // остальные типы не имеют методов
            }
        }
        return null
    }

    // ------------------------------------------------- встроенные функции

    private fun registerBuiltins() {
        fun bi(name: String, fn: (List<PyVal>, Map<String, PyVal>) -> PyVal) {
            val b = PyBuiltin(name, fn)
            bound[name] = b
            globals.set(name, b)
        }

        bi("print") { args, kwargs ->
            val sep = kwargs["sep"]?.let { pyStr(it) } ?: " "
            val end = kwargs["end"]?.let { pyStr(it) } ?: "\n"
            host.out(args.joinToString(sep) { pyStr(it) } + end)
            PyNone
        }

        bi("input") { args, _ ->
            val prompt = args.getOrNull(0)?.let { pyStr(it) } ?: ""
            host.prompt(prompt)
            PyStr(host.readLine())
        }

        bi("len") { args, _ ->
            when (val v = args.getOrNull(0)) {
                is PyStr -> PyInt(v.v.length.toLong())
                is PyList -> PyInt(v.items.size.toLong())
                is PyTuple -> PyInt(v.items.size.toLong())
                is PyDict -> PyInt(v.entries.size.toLong())
                is PyRange -> PyInt(v.length())
                else -> err("у '${v?.let { pyTypeName(it) } ?: "None"}' нет длины")
            }
        }

        bi("int") { args, _ ->
            when (val v = args.getOrNull(0)) {
                null -> PyInt(0)
                is PyInt -> v
                is PyBool -> PyInt(if (v.v) 1L else 0L)
                is PyFloat -> PyInt(v.v.toLong())
                is PyStr -> {
                    val t = v.v.trim()
                    t.toLongOrNull() ?: err("не удалось преобразовать '${v.v}' в число")
                }
                else -> err("нельзя преобразовать '${pyTypeName(v)}' в int")
            }
        }

        bi("float") { args, _ ->
            when (val v = args.getOrNull(0)) {
                null -> PyFloat(0.0)
                is PyInt -> PyFloat(v.v.toDouble())
                is PyFloat -> v
                is PyBool -> PyFloat(if (v.v) 1.0 else 0.0)
                is PyStr -> v.v.trim().toDoubleOrNull() ?: err("не удалось преобразовать '${v.v}' в число")
                else -> err("нельзя преобразовать '${pyTypeName(v)}' в float")
            }
        }

        bi("str") { args, _ -> PyStr(args.getOrNull(0)?.let { pyStr(it) } ?: "") }

        bi("bool") { args, _ -> PyBool(args.getOrNull(0)?.truthy() ?: false) }

        bi("abs") { args, _ ->
            when (val v = args.getOrNull(0)) {
                is PyInt -> PyInt(Math.abs(v.v))
                is PyFloat -> PyFloat(Math.abs(v.v))
                is PyBool -> PyInt(if (v.v) 1L else 0L)
                else -> err("abs() требует число")
            }
        }

        bi("min") { args, _ -> minMax(args, min = true) }
        bi("max") { args, _ -> minMax(args, min = false) }

        bi("round") { args, _ ->
            val x = args.getOrNull(0) ?: err("round() требует число")
            val n = args.getOrNull(1)?.let { asLong(it) }
            if (n == null && x is PyInt) return@bi x
            val f = asFloatOrNull(x) ?: err("round() требует число")
            if (n == null) {
                PyInt(Math.rint(f).toLong())
            } else {
                val mult = Math.pow(10.0, n.toDouble())
                if (!mult.isFinite()) PyFloat(f) else {
                    val r = Math.rint(f * mult) / mult
                    if (n <= 0) PyInt(r.toLong()) else PyFloat(r)
                }
            }
        }

        bi("sum") { args, _ ->
            val it = args.getOrNull(0) ?: err("sum() требует итерируемый объект")
            var acc: PyVal = PyInt(0)
            for (v in iterSeq(it, currentLine)) acc = binop("+", acc, v, currentLine)
            acc
        }

        bi("type") { args, _ ->
            val v = args.getOrNull(0) ?: PyNone
            PyStr(if (v === PyNone) "NoneType" else pyTypeName(v))
        }

        bi("list") { args, _ ->
            val v = args.getOrNull(0) ?: return@bi PyList(ArrayList())
            when (v) {
                is PyList -> PyList(ArrayList(v.items))
                is PyTuple -> PyList(ArrayList(v.items))
                is PyStr -> PyList(v.v.map { PyStr(it.toString()) })
                is PyRange -> PyList(iterSeq(v, currentLine).toMutableList())
                is PyDict -> PyList(v.entries.keys.mapTo(ArrayList()) {
                    when (it) {
                        is String -> PyStr(it)
                        is Long -> PyInt(it)
                        is Boolean -> PyBool(it)
                        else -> PyStr(it.toString())
                    }
                })
                else -> err("'${pyTypeName(v)}' нельзя преобразовать в list")
            }
        }

        bi("dict") { args, _ ->
            if (args.isEmpty()) PyDict(LinkedHashMap()) else err("dict() с аргументами не поддерживается")
        }

        bi("tuple") { args, _ ->
            val v = args.getOrNull(0) ?: return@bi PyTuple(emptyList())
            when (v) {
                is PyList -> PyTuple(ArrayList(v.items))
                is PyTuple -> v
                is PyStr -> PyTuple(v.v.map { PyStr(it.toString()) })
                is PyRange -> PyTuple(iterSeq(v, currentLine).toList())
                else -> err("'${pyTypeName(v)}' нельзя преобразовать в tuple")
            }
        }

        bi("range") { args, _ ->
            fun rl(i: Int): Long = asLong(args.getOrNull(i) ?: err("range() требует числа"))
                ?: err("range() требует числа")
            when (args.size) {
                1 -> PyRange(0, rl(0), 1)
                2 -> PyRange(rl(0), rl(1), 1)
                3 -> {
                    val st = rl(2)
                    if (st == 0L) err("шаг range() не может быть нулём")
                    PyRange(rl(0), rl(1), st)
                }
                else -> err("range() принимает 1–3 аргумента")
            }
        }

        bi("enumerate") { args, _ ->
            val it = args.getOrNull(0) ?: err("enumerate() требует итерируемый объект")
            val start = args.getOrNull(1)?.let { asLong(it) } ?: 0L
            val out = ArrayList<PyVal>()
            var i = start
            for (v in iterSeq(it, currentLine)) {
                out.add(PyTuple(listOf(PyInt(i), v)))
                i++
            }
            PyList(out)
        }

        bi("sorted") { args, _ ->
            val it = args.getOrNull(0) ?: err("sorted() требует итерируемый объект")
            val items = iterSeq(it, currentLine).toMutableList()
            items.sortWith { x, y -> compareVals(x, y, currentLine) }
            PyList(items)
        }

        bi("reversed") { args, _ ->
            val it = args.getOrNull(0) ?: err("reversed() требует итерируемый объект")
            val items = iterSeq(it, currentLine).toMutableList()
            items.reverse()
            PyList(items)
        }

        bi("isinstance") { args, _ ->
            val v = args.getOrNull(0) ?: PyNone
            val t = args.getOrNull(1)
            val tn = when (t) {
                is PyStr -> t.v
                is PyTuple -> (t.items.firstOrNull() as? PyStr)?.v ?: ""
                else -> ""
            }
            val actual = if (v === PyNone) "None" else pyTypeName(v)
            val ok = tn == actual ||
                (tn == "int" && actual == "bool") ||
                (tn == "float" && actual == "int") ||
                (tn == "object")
            PyBool(ok)
        }
    }

    private fun minMax(args: List<PyVal>, min: Boolean): PyVal {
        val seq: Sequence<PyVal> = when {
            args.isEmpty() -> err(if (min) "min() пуст" else "max() пуст")
            args.size == 1 -> iterSeq(args[0], currentLine)
            else -> args.asSequence()
        }
        var best: PyVal? = null
        for (v in seq) {
            if (best == null) {
                best = v
            } else {
                val c = compareVals(v, best, currentLine)
                if ((min && c < 0) || (!min && c > 0)) best = v
            }
        }
        return best ?: err(if (min) "min() пуст" else "max() пуст")
    }

    // ------------------------------------------------------------- модули

    private val modules: Map<String, PyModule> by lazy { buildModules() }

    private fun bi2(name: String, fn: (List<PyVal>, Map<String, PyVal>) -> PyVal): PyBuiltin =
        PyBuiltin(name, fn)

    private fun num1(name: String, f: (Double) -> Double): PyBuiltin =
        bi2(name) { args, _ ->
            val x = asFloatOrNull(args.getOrNull(0) ?: err("${name}() требует число"))
                ?: err("${name}() требует число")
            PyFloat(f(x))
        }

    private fun num2(name: String, f: (Double, Double) -> Double): PyBuiltin =
        bi2(name) { args, _ ->
            val a = asFloatOrNull(args.getOrNull(0) ?: err("${name}() требует числа"))
                ?: err("${name}() требует числа")
            val b = asFloatOrNull(args.getOrNull(1) ?: err("${name}() требует числа"))
                ?: err("${name}() требует числа")
            PyFloat(f(a, b))
        }

    private fun buildModules(): Map<String, PyModule> {
        val math = PyModule(
            "math",
            mapOf(
                "pi" to PyFloat(Math.PI),
                "e" to PyFloat(Math.E),
                "tau" to PyFloat(Math.PI * 2),
                "sqrt" to num1("sqrt") {
                    if (it < 0) err("корень из отрицательного числа")
                    Math.sqrt(it)
                },
                "sin" to num1("sin", ::Math_sin),
                "cos" to num1("cos", ::Math_cos),
                "tan" to num1("tan", ::Math_tan),
                "asin" to num1("asin", Math::asin),
                "acos" to num1("acos", Math::acos),
                "atan" to num1("atan", Math::atan),
                "atan2" to num2("atan2", Math::atan2),
                "radians" to num1("radians") { Math.toRadians(it) },
                "degrees" to num1("degrees") { Math.toDegrees(it) },
                "floor" to num1("floor", Math::floor),
                "ceil" to num1("ceil", Math::ceil),
                "fabs" to num1("fabs", Math::abs),
                "log" to bi2("log") { args, _ ->
                    val x = asFloatOrNull(args.getOrNull(0) ?: err("log() требует число"))
                        ?: err("log() требует число")
                    if (x <= 0) err("логарифм определён только для положительных чисел")
                    if (args.size >= 2) {
                        val base = asFloatOrNull(args[1]) ?: err("log() требует число")
                        if (base <= 0 || base == 1.0) err("неверная основа логарифма")
                        PyFloat(Math.log(x) / Math.log(base))
                    } else {
                        PyFloat(Math.log(x))
                    }
                },
                "log10" to num1("log10") {
                    if (it <= 0) err("логарифм определён только для положительных чисел")
                    Math.log10(it)
                },
                "pow" to num2("pow", Math::pow),
                "hypot" to num2("hypot", Math::hypot)
            )
        )

        val random = PyModule(
            "random",
            mapOf(
                "random" to bi2("random") { _, _ -> PyFloat(Math.random()) },
                "uniform" to bi2("uniform") { args, _ ->
                    val a = asFloatOrNull(args.getOrNull(0) ?: err("uniform() требует числа"))
                        ?: err("uniform() требует числа")
                    val b = asFloatOrNull(args.getOrNull(1) ?: err("uniform() требует числа"))
                        ?: err("uniform() требует числа")
                    PyFloat(a + Math.random() * (b - a))
                },
                "randint" to bi2("randint") { args, _ ->
                    val a = asLong(args.getOrNull(0) ?: err("randint() требует числа"))
                        ?: err("randint() требует числа")
                    val b = asLong(args.getOrNull(1) ?: err("randint() требует числа"))
                        ?: err("randint() требует числа")
                    if (a > b) err("randint(): первое число больше второго")
                    val span = b - a + 1.0
                    PyInt(a + Math.floor(Math.random() * span).toLong())
                },
                "choice" to bi2("choice") { args, _ ->
                    val it = args.getOrNull(0) ?: err("choice() требует последовательность")
                    val items = iterSeq(it, currentLine).toList()
                    if (items.isEmpty()) err("выбор из пустой последовательности")
                    items[Math.floor(Math.random() * items.size).toInt()]
                }
            )
        )

        val turtle = PyModule("turtle", turtleMethods())

        return mapOf("math" to math, "random" to random, "turtle" to turtle)
    }

    // маленькие обёртки, чтобы не конфликтовать с kotlin.math через ::Мath
    private fun Math_sin(x: Double): Double = Math.sin(x)
    private fun Math_cos(x: Double): Double = Math.cos(x)
    private fun Math_tan(x: Double): Double = Math.tan(x)

    // ------------------------------------------------------------ turtle

    private fun turtleMethods(): Map<String, PyVal> {
        fun biN(name: String, fn: (List<PyVal>) -> Unit): PyBuiltin =
            bi2(name) { args, _ -> fn(args); PyNone }

        fun colorArg(args: List<PyVal>): Int = when {
            args.size == 3 -> {
                val r = asLong(args[0]); val g = asLong(args[1]); val b = asLong(args[2])
                if (r == null || g == null || b == null) err("цвет rgb должен быть из чисел")
                if (r !in 0..255 || g !in 0..255 || b !in 0..255) err("цвет rgb вне диапазона 0–255")
                (0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b).toInt()
            }
            args.size == 1 -> parseColor(args[0])
            else -> err("неверный формат цвета")
        }

        return mapOf(
            "forward" to biN("forward") { turtle.forward(argNum(it, 0, "forward")) },
            "fd" to biN("fd") { turtle.forward(argNum(it, 0, "fd")) },
            "backward" to biN("backward") { turtle.forward(-argNum(it, 0, "backward")) },
            "bk" to biN("bk") { turtle.forward(-argNum(it, 0, "bk")) },
            "back" to biN("back") { turtle.forward(-argNum(it, 0, "back")) },
            "right" to biN("right") { turtle.turn(argNum(it, 0, "right")) },
            "rt" to biN("rt") { turtle.turn(argNum(it, 0, "rt")) },
            "left" to biN("left") { turtle.turn(-argNum(it, 0, "left")) },
            "lt" to biN("lt") { turtle.turn(-argNum(it, 0, "lt")) },
            "goto" to biN("goto") {
                turtle.goto(argNum(it, 0, "goto"), argNum(it, 1, "goto"))
            },
            "setpos" to biN("setpos") {
                turtle.goto(argNum(it, 0, "setpos"), argNum(it, 1, "setpos"))
            },
            "setposition" to biN("setposition") {
                turtle.goto(argNum(it, 0, "setposition"), argNum(it, 1, "setposition"))
            },
            "setheading" to biN("setheading") { turtle.setHeading(argNum(it, 0, "setheading")) },
            "seth" to biN("seth") { turtle.setHeading(argNum(it, 0, "seth")) },
            "penup" to biN("penup") { turtle.penDown = false },
            "pu" to biN("pu") { turtle.penDown = false },
            "up" to biN("up") { turtle.penDown = false },
            "pendown" to biN("pendown") { turtle.penDown = true },
            "pd" to biN("pd") { turtle.penDown = true },
            "down" to biN("down") { turtle.penDown = true },
            "pencolor" to biN("pencolor") { turtle.color = colorArg(it) },
            "color" to biN("color") { turtle.color = colorArg(it) },
            "pensize" to biN("pensize") {
                turtle.width = argNum(it, 0, "pensize").coerceAtLeast(1.0)
            },
            "width" to biN("width") {
                turtle.width = argNum(it, 0, "width").coerceAtLeast(1.0)
            },
            "speed" to biN("speed") { /* визуальная скорость не влияет на результат */ },
            "circle" to biN("circle") {
                turtle.circle(
                    argNum(it, 0, "circle"),
                    if (it.size >= 2) argNum(it, 1, "circle") else 360.0
                )
            },
            "home" to biN("home") {
                turtle.goto(0.0, 0.0)
                turtle.setHeading(0.0)
            },
            "clear" to biN("clear") { turtle.clear() },
            "reset" to biN("reset") { turtle.reset() },
            "hideturtle" to biN("hideturtle") { turtle.visible = false; turtle.emit() },
            "ht" to biN("ht") { turtle.visible = false; turtle.emit() },
            "showturtle" to biN("showturtle") { turtle.visible = true; turtle.emit() },
            "st" to biN("st") { turtle.visible = true; turtle.emit() },
            "bgcolor" to biN("bgcolor") { bridgeBg(colorArg(it)) },
            "position" to biN("position") { PyTuple(listOf(PyFloat(turtle.x), PyFloat(turtle.y))) },
            "pos" to biN("pos") { PyTuple(listOf(PyFloat(turtle.x), PyFloat(turtle.y))) },
            "xcor" to biN("xcor") { PyFloat(turtle.x) },
            "ycor" to biN("ycor") { PyFloat(turtle.y) },
            "heading" to biN("heading") { PyFloat(turtle.heading) },
            "done" to biN("done") { throw PyDone() },
            "exit" to biN("exit") { throw PyDone() }
        )
    }

    private fun bridgeBg(color: Int) {
        host.turtle().bgcolor(color)
    }

    private fun argNum(args: List<PyVal>, i: Int, fn: String): Double {
        val a = args.getOrNull(i) ?: err("${fn}() требует аргумент")
        return asFloatOrNull(a) ?: err("${fn}() требует число")
    }

    private fun parseColor(v: PyVal): Int {
        when (v) {
            is PyStr -> {
                val s = v.v.trim().lowercase()
                NAMED_COLORS[s]?.let { return it }
                if (s.startsWith("#") && (s.length == 7 || s.length == 4)) {
                    val hex = if (s.length == 4) {
                        "#${s[1]}${s[1]}${s[2]}${s[2]}${s[3]}${s[3]}"
                    } else s
                    try {
                        return (0xFF000000 or hex.substring(1).toLong(16)).toInt()
                    } catch (e: Exception) {
                        throw PyError(currentLine, "неверный цвет '${v.v}'")
                    }
                }
                throw PyError(currentLine, "неизвестный цвет '${v.v}'")
            }
            is PyTuple, is PyList -> {
                val items = (v as? PyList)?.items ?: (v as PyTuple).items
                if (items.size == 3) {
                    val r = asLong(items[0]); val g = asLong(items[1]); val b = asLong(items[2])
                    if (r != null && g != null && b != null &&
                        r in 0..255 && g in 0..255 && b in 0..255
                    ) {
                        return (0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b).toInt()
                    }
                    throw PyError(currentLine, "цвет rgb вне диапазона 0–255")
                }
                throw PyError(currentLine, "цвет должен быть строкой или (r, g, b)")
            }
            else -> throw PyError(currentLine, "цвет должен быть строкой или (r, g, b)")
        }
    }

    private val NAMED_COLORS = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "brown" to 0xFFA52A2A.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "pink" to 0xFFFFC0CB.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "navy" to 0xFF000080.toInt(),
        "olive" to 0xFF808000.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "teal" to 0xFF008080.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "gold" to 0xFFFFD700.toInt(),
        "indigo" to 0xFF4B0082.toInt(),
        "violet" to 0xFFEE82EE.toInt(),
        "coral" to 0xFFFF7F50.toInt(),
        "turquoise" to 0xFF40E0D0.toInt(),
        "salmon" to 0xFFFA8072.toInt(),
        "khaki" to 0xFFF0E68C.toInt(),
        "crimson" to 0xFFDC143C.toInt()
    )

    // --------------------------------------------------------- модель turtle

    private inner class TurtleSim {
        var x = 0.0
        var y = 0.0
        var heading = 0.0
        var penDown = true
        var visible = true
        var color: Int = 0xFF000000.toInt()
        var width = 1.0

        private val bridge: TurtleBridge get() = host.turtle()

        fun emit() = bridge.marker(x, y, heading, visible, color)

        fun forward(d: Double) {
            val rad = Math.toRadians(heading)
            val nx = x + sin(rad) * d
            val ny = y + cos(rad) * d
            if (penDown) bridge.segment(x, y, nx, ny, color, width)
            x = nx
            y = ny
            emit()
        }

        fun turn(deg: Double) {
            heading = ((heading + deg) % 360.0 + 360.0) % 360.0
            emit()
        }

        fun setHeading(h: Double) {
            heading = ((h % 360.0) + 360.0) % 360.0
            emit()
        }

        fun goto(nx: Double, ny: Double) {
            if (penDown) bridge.segment(x, y, nx, ny, color, width)
            x = nx
            y = ny
            emit()
        }

        fun circle(r: Double, extent: Double) {
            if (r == 0.0) {
                // окружность радиуса 0 недопустима в python turtle
                err("радиус circle() не может быть нулём")
            }
            val steps = max(6, min(720, (abs(extent) / 6.0).toInt()))
            val stepLen = abs(r) * Math.toRadians(abs(extent)) / steps
            val dir = (if (r >= 0) -1.0 else 1.0) * (if (extent < 0) -1.0 else 1.0)
            val stepT = abs(extent) / steps
            for (i in 0 until steps) {
                forward(stepLen)
                turn(dir * stepT)
            }
            emit()
        }

        fun clear() {
            bridge.clear()
        }

        fun reset() {
            x = 0.0
            y = 0.0
            heading = 0.0
            penDown = true
            visible = true
            color = 0xFF000000.toInt()
            width = 1.0
            bridge.clear()
            emit()
        }
    }
}
