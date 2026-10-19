package com.htmleditor.app.py

// ------------------------------------------------------------ ошибки / поток

/** Остановка выполнения пользователем (кнопка Stop). */
class PyStop : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

/** Штатное завершение turtle.done() / exit(). */
class PyDone : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

/** Ошибка исполнения с номером строки. */
class PyError(val line: Int, message: String) : Exception(message)

/** Среда исполнения, обеспечиваемая приложением. */
interface ExecHost {
    /** Вывод в консоль (с завершающим переводом строки, если нужен). */
    fun out(text: String)

    /** Приглашение input() без перевода строки. */
    fun prompt(text: String)

    /** Блокирующее чтение строки пользователем. */
    fun readLine(): String

    /** Проверка запроса остановки; кидает PyStop. */
    fun tick()

    /** Мост к холсту turtle. */
    fun turtle(): TurtleBridge
}

interface TurtleBridge {
    fun segment(x1: Double, y1: Double, x2: Double, y2: Double, color: Int, width: Double)
    fun marker(x: Double, y: Double, heading: Double, visible: Boolean, color: Int)
    fun bgcolor(color: Int)
    fun clear()
}

// ----------------------------------------------------------------- значения

sealed class PyVal {
    open fun truthy(): Boolean = true
    open fun typeName(): String = "?"
}

object PyNone : PyVal() {
    override fun truthy(): Boolean = false
    override fun typeName(): String = "None"
    override fun toString(): String = "None"
}

class PyBool(val v: Boolean) : PyVal() {
    override fun truthy(): Boolean = v
    override fun typeName(): String = "bool"
}

class PyInt(val v: Long) : PyVal() {
    override fun typeName(): String = "int"
}

class PyFloat(val v: Double) : PyVal() {
    override fun typeName(): String = "float"
}

class PyStr(val v: String) : PyVal() {
    override fun truthy(): Boolean = v.isNotEmpty()
    override fun typeName(): String = "str"
}

class PyList(val items: MutableList<PyVal>) : PyVal() {
    override fun truthy(): Boolean = items.isNotEmpty()
    override fun typeName(): String = "list"
}

class PyTuple(val items: List<PyVal>) : PyVal() {
    override fun truthy(): Boolean = items.isNotEmpty()
    override fun typeName(): String = "tuple"
}

class PyDict(val entries: LinkedHashMap<Any, PyVal>) : PyVal() {
    override fun truthy(): Boolean = entries.isNotEmpty()
    override fun typeName(): String = "dict"
}

class PyRange(val start: Long, val stop: Long, val step: Long) : PyVal() {
    fun length(): Long {
        if (step > 0 && start >= stop) return 0
        if (step < 0 && start <= stop) return 0
        val diff = stop - start
        return (diff / step) + if (diff % step == 0L) 0 else 1
    }

    fun at(i: Long): Long = start + i * step

    override fun truthy(): Boolean = length() > 0
    override fun typeName(): String = "range"
}

data class PyParam(val name: String, val default: PyVal?)

class PyFunc(
    val name: String,
    val params: List<PyParam>,
    val body: List<Stmt>,
    var scope: Env?
) : PyVal() {
    override fun typeName(): String = "function"
}

class PyBuiltin(
    val name: String,
    val fn: (args: List<PyVal>, kwargs: Map<String, PyVal>) -> PyVal
) : PyVal() {
    override fun typeName(): String = "builtin_function_or_method"
}

class PyModule(val name: String, val attrs: Map<String, PyVal>) : PyVal() {
    override fun typeName(): String = "module"
}

class Env(val parent: Env?) {
    val vars = HashMap<String, PyVal>()

    fun get(name: String): PyVal? {
        var e: Env? = this
        while (e != null) {
            e.vars[name]?.let { return it }
            e = e.parent
        }
        return null
    }

    fun set(name: String, value: PyVal) {
        vars[name] = value
    }
}

// ------------------------------------------------------------- repr / str

fun pyTypeName(v: PyVal): String = v.typeName()

fun pyStr(v: PyVal): String = when (v) {
    is PyStr -> v.v
    is PyBool -> if (v.v) "True" else "False"
    is PyInt -> v.v.toString()
    is PyFloat -> formatFloat(v.v)
    PyNone -> "None"
    else -> pyRepr(v)
}

private fun formatFloat(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    return d.toString()
}

fun pyRepr(v: PyVal): String = when (v) {
    is PyStr -> quoteStr(v.v)
    is PyBool -> if (v.v) "True" else "False"
    is PyInt -> v.v.toString()
    is PyFloat -> formatFloat(v.v)
    PyNone -> "None"
    is PyList -> v.items.joinToString(", ", "[", "]") { pyRepr(it) }
    is PyTuple -> if (v.items.size == 1) {
        "(" + pyRepr(v.items[0]) + ",)"
    } else {
        v.items.joinToString(", ", "(", ")") { pyRepr(it) }
    }
    is PyDict -> v.entries.entries.joinToString(", ", "{", "}") { (k, value) ->
        keyRepr(k) + ": " + pyRepr(value)
    }
    is PyRange -> "range(${v.start}, ${v.stop}${if (v.step != 1L) ", ${v.step}" else ""})"
    is PyFunc -> "<function ${v.name}>"
    is PyBuiltin -> "<built-in function ${v.name}>"
    is PyModule -> "<module '${v.name}'>"
    else -> "<object>"
}

private fun keyRepr(k: Any): String = when (k) {
    is String -> quoteStr(k)
    is Boolean -> if (k) "True" else "False"
    else -> k.toString()
}

private fun quoteStr(s: String): String {
    val sb = StringBuilder("'")
    for (c in s) {
        when (c) {
            '\'' -> sb.append("\\'")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
    }
    sb.append('\'')
    return sb.toString()
}

/** Структурное/числовое равенство. */
fun pyEquals(a: PyVal, b: PyVal): Boolean {
    if ((a is PyList || a is PyDict || a is PyFunc) && a === b) return true
    if ((b is PyList || b is PyDict || b is PyFunc) && a === b) return true
    if (a is PyInt && b is PyBool) return a.v == (if (b.v) 1L else 0L)
    if (a is PyBool && b is PyInt) return (if (a.v) 1L else 0L) == b.v
    return when {
        a is PyInt && b is PyInt -> a.v == b.v
        a is PyFloat && b is PyFloat -> a.v == b.v
        (a is PyInt || a is PyFloat) && (b is PyInt || b is PyFloat) ->
            asDouble(a) == asDouble(b)
        a is PyBool && b is PyBool -> a.v == b.v
        a is PyStr && b is PyStr -> a.v == b.v
        a === PyNone && b === PyNone -> true
        a is PyList && b is PyList ->
            a.items.size == b.items.size && a.items.indices.all { pyEquals(a.items[it], b.items[it]) }
        a is PyTuple && b is PyTuple ->
            a.items.size == b.items.size && a.items.indices.all { pyEquals(a.items[it], b.items[it]) }
        a is PyList && b is PyTuple ->
            a.items.size == b.items.size && a.items.indices.all { pyEquals(a.items[it], b.items[it]) }
        a is PyTuple && b is PyList ->
            a.items.size == b.items.size && a.items.indices.all { pyEquals(a.items[it], b.items[it]) }
        a is PyDict && b is PyDict -> a.entries == b.entries
        else -> false
    }
}

fun asDouble(v: PyVal): Double = when (v) {
    is PyInt -> v.v.toDouble()
    is PyFloat -> v.v
    is PyBool -> if (v.v) 1.0 else 0.0
    else -> 0.0
}

/** Ключ словаря: уникальное представление для Primitives. */
fun dictKey(v: PyVal): Any = when (v) {
    is PyStr -> v.v
    is PyInt -> v.v
    is PyBool -> v.v
    PyNone -> "#PY_NONE_KEY#"
    is PyFloat -> v.v
    else -> throw IllegalArgumentException("bad key")
}
