package com.glassbrowser.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Кольцевой журнал событий: прокси (тег LAN) и перехват WebView (тег WEB).
 * Подписчики (лист прокси) получают каждую строку в UI-потоке.
 */
object LogHub {

    const val LAN = "LAN"
    const val WEB = "WEB"

    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$tag] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        for (l in listeners) {
            try { l(line) } catch (e: Exception) { /* ignore */ }
        }
    }

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** Строки журнала с тегом (без префикса времени и тега). */
    fun dump(tag: String): String {
        val marker = "[$tag] "
        val sb = StringBuilder()
        synchronized(lines) {
            for (l in lines) {
                val p = l.indexOf(marker)
                if (p >= 0) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(l.substring(p + marker.length))
                }
            }
        }
        return sb.toString()
    }

    fun clear(tag: String? = null) {
        synchronized(lines) {
            if (tag == null) lines.clear()
            else {
                val it = lines.iterator()
                while (it.hasNext()) {
                    if (it.next().contains("[$tag]")) it.remove()
                }
            }
        }
    }
}
