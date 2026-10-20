package com.glassbrowser.app

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * Локальная БД приложения (SQLite): консоль SQL в приложении +
 * JSON-результат для внедрения в страницу (window.__glassSql).
 */
class SqlStore(context: Context) : SQLiteOpenHelper(context, "glass_browser.db", null, 1) {

    data class Result(
        val columns: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val error: String? = null,
        val affected: Int = 0,
        val select: Boolean = false
    ) {
        fun text(): String {
            if (error != null) return "Ошибка: $error"
            if (!select) return "OK, затронуто строк: $affected"
            if (rows.isEmpty()) return "Пустой результат (0 строк)"
            val sb = StringBuilder()
            // заголовок
            val widths = IntArray(columns.size)
            for (i in columns.indices) widths[i] = columns[i].length
            for (r in rows) {
                for (i in r.indices) {
                    if (i < widths.size) widths[i] = maxOf(widths[i], r[i].length)
                }
            }
            sb.append(columns.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString(" | "))
            sb.append('\n')
            sb.append(widths.joinToString("-+-") { "-".repeat(it) }).append('\n')
            for (r in rows) {
                val cells = ArrayList<String>()
                for (i in widths.indices) {
                    val v = if (i < r.size) r[i] else ""
                    cells.add(v.padEnd(widths[i]))
                }
                sb.append(cells.joinToString(" | ")).append('\n')
            }
            sb.append("(").append(rows.size).append(" строк)")
            return sb.toString()
        }

        fun json(): String {
            if (error != null || !select) return "null"
            val cols = JSONArray()
            for (c in columns) cols.put(c)
            val rowsArr = JSONArray()
            for (r in rows) {
                val cellArr = JSONArray()
                for (v in r) cellArr.put(v)
                rowsArr.put(cellArr)
            }
            val obj = org.json.JSONObject()
            obj.put("columns", cols)
            obj.put("rows", rowsArr)
            return obj.toString()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS meta (" +
                "_k TEXT PRIMARY KEY, _v TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, body TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // схема v1 — пусто
    }

    /** Выполняет один оператор: SELECT/WITH → таблица, иначе → execSQL. */
    fun run(sql: String): Result {
        val s = sql.trim().trimEnd(';')
        if (s.isEmpty()) return Result(error = "Пустой запрос")
        val u = s.uppercase()
        return try {
            if (u.startsWith("SELECT") || u.startsWith("WITH") || u.startsWith("PRAGMA")) {
                query(s)
            } else {
                exec(s)
            }
        } catch (e: Exception) {
            Result(error = e.message ?: e.toString())
        }
    }

    private fun query(sql: String): Result {
        val db = readableDatabase
        db.rawQuery(sql, null).use { c: Cursor ->
            return cursorToResult(c)
        }
    }

    private fun exec(sql: String): Result {
        val db = writableDatabase
        if (sql.startsWith("INSERT", true) || sql.startsWith("UPDATE", true) ||
            sql.startsWith("DELETE", true)
        ) {
            // поддерживаем возврата count
            return try {
                val st = db.compileStatement(sql)
                val n = st.executeUpdateDelete()
                st.close()
                Result(affected = n)
            } catch (e: Exception) {
                // не все операторы поддерживают compileStatement — базовый путь
                db.execSQL(sql)
                Result(affected = 1)
            }
        }
        db.execSQL(sql)
        return Result(affected = 1)
    }

    private fun cursorToResult(c: Cursor): Result {
        val columns = ArrayList<String>()
        for (i in 0 until c.columnCount) columns.add(c.getColumnName(i))
        val rows = ArrayList<List<String>>()
        while (c.moveToNext()) {
            val row = ArrayList<String>(columns.size)
            for (i in 0 until c.columnCount) {
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> row.add("NULL")
                    Cursor.FIELD_TYPE_INTEGER -> row.add(c.getLong(i).toString())
                    Cursor.FIELD_TYPE_FLOAT -> row.add(c.getDouble(i).toString())
                    else -> {
                        val v = c.getString(i) ?: ""
                        row.add(if (v.length > 4000) v.substring(0, 4000) + "…" else v)
                    }
                }
            }
            rows.add(row)
        }
        return Result(columns = columns, rows = rows, select = true)
    }
}
