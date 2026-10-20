package com.glassbrowser.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Реальный LAN HTTP-прокси на ServerSocket:
 *  - обычные запросы (GET/POST с абсолютным URI) — форвард с подстановкой
 *    заголовка Cookie из CookieStore (способ 4);
 *  - CONNECT — туннель как есть (HTTPS), без чтения трафика.
 * Живёт в своём потоке; останавливается с onStop MainActivity.
 */
object ProxyServer {

    @Volatile var running = false
        private set

    @Volatile private var server: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    private val startedAt = AtomicInteger(0)

    /**
     * Маячок: означает, что набор cookies изменился (способ 4).
     * Сам заголовок прокси собирает из CookieStore.headerFor(hост) при каждом запросе.
     */
    fun setHeaderCookie(v: String) {
        // намеренно без состояния — домен-матчинг в CookieStore
    }

    fun currentPort(): Int = server?.localPort ?: 0

    /** LAN IP (внешний) или 127.0.0.1 */
    fun lanAddress(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                for (a in addrs) {
                    if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                    if (a is java.net.Inet4Address) return a.hostAddress ?: continue
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "127.0.0.1"
    }

    fun addressString(): String {
        val p = currentPort()
        return if (running && p > 0) "${lanAddress()}:$p" else ""
    }

    /** Запуск. null — успех, иначе текст ошибки. */
    fun start(): String? {
        if (running) return null
        var lastErr = "неизвестная ошибка"
        for (port in intArrayOf(8888, 8889, 8890, 8891, 8892, 8895)) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                server = ss
                running = true
                startedAt.incrementAndGet()
                val gen = startedAt.get()
                worker = Thread({
                    acceptLoop(ss, gen)
                }, "glass-proxy").also {
                    it.isDaemon = true
                    it.start()
                }
                LogHub.log(LogHub.LAN, "прокси запущен на 0.0.0.0:$port (${lanAddress()})")
                return null
            } catch (e: Exception) {
                lastErr = e.message ?: e.toString()
            }
        }
        return lastErr
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { /* ignore */ }
        server = null
        worker = null
        LogHub.log(LogHub.LAN, "прокси остановлен")
    }

    private fun acceptLoop(ss: ServerSocket, gen: Int) {
        while (running && startedAt.get() == gen) {
            val client = try { ss.accept() } catch (e: Exception) { break }
            val t = Thread({
                try { handle(client) } catch (e: Exception) {
                    LogHub.log(LogHub.LAN, "ошибка: ${e.message}")
                } finally {
                    try { client.close() } catch (e: Exception) { /* ignore */ }
                }
            }, "glass-proxy-conn")
            t.isDaemon = true
            t.start()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = BufferedOutputStream(client.getOutputStream())

        val (firstLine, headers) = readRequest(input) ?: run {
            client.close()
            return
        }
        val parts = firstLine.split(" ")
        if (parts.size < 3) {
            out.write(badRequest()); out.flush()
            return
        }
        val method = parts[0].uppercase()
        val target = parts[1]

        if (method == "CONNECT") {
            // туннель для HTTPS — не читаем содержимое
            val hp = target.split(":")
            val h = hp[0]
            val p = hp.getOrElse(1) { "443" }.toIntOrNull() ?: 443
            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(h, p), 10_000)
            } catch (e: Exception) {
                out.write(badGateway()); out.flush()
                LogHub.log(LogHub.LAN, "CONNECT $h:$p — нет соединения")
                return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            LogHub.log(LogHub.LAN, "CONNECT $h:$p (туннель)")
            relay(client, upstream)
            return
        }

        // абсолютный URI: GET http://host/path HTTP/1.1
        val t = parseTarget(target, headers) ?: run {
            out.write(badRequest()); out.flush()
            return
        }
        val scheme = t.scheme
        val host = t.host
        val port = t.port
        val path = t.path

        // заголовок Cookie для обычных HTTP (способ 4)
        val effHeaders = ArrayList<String>()
        var hasCookie = false
        for (line in headers) {
            val l = line.lowercase()
            if (l.startsWith("proxy-connection:")) continue
            if (l.startsWith("cookie:")) hasCookie = true
            if (l.startsWith("connection:")) {
                effHeaders.add("Connection: close")
                continue
            }
            effHeaders.add(line)
        }
        if (!hasCookie) {
            val c = headerForHost(host)
            if (c.isNotEmpty()) effHeaders.add("Cookie: $c")
        }

        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(host, port), 10_000)
            upstream.soTimeout = 60_000
            val uo = BufferedOutputStream(upstream.getOutputStream())
            val ui = BufferedInputStream(upstream.getInputStream())

            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            var hostSeen = false
            for (line in effHeaders) {
                if (line.lowercase().startsWith("host:")) hostSeen = true
                sb.append(line).append("\r\n")
            }
            if (!hostSeen) sb.append("Host: ").append(host)
                .append(if (port != 80) ":$port" else "").append("\r\n")
            sb.append("\r\n")
            uo.write(sb.toString().toByteArray())
            uo.flush()

            // тело запроса (POST)
            val bodyLen = headerValue(effHeaders, "content-length")?.toIntOrNull() ?: 0
            if (bodyLen > 0) {
                copyN(input, uo, bodyLen)
                uo.flush()
            }
            LogHub.log(
                LogHub.LAN,
                "$method $host$path" + (if (headerForHost(host).isNotEmpty()) " +Cookie" else "")
            )

            // ответ — до закрытия (Connection: close форсируем)
            copyUntilEof(ui, out)
            out.flush()
        } finally {
            try { upstream.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    /** Cookies для хоста — домен-матчинг делает CookieStore (не утекают чужим сайтам). */
    private fun headerForHost(host: String): String {
        return try {
            CookieStore.headerFor(host)
        } catch (e: Exception) {
            ""
        }
    }

    private fun readRequest(input: InputStream): Pair<String, List<String>>? {
        val first = readLine(input) ?: return null
        if (first.isEmpty()) return null
        val headers = ArrayList<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers.add(line)
            if (headers.size > 64) break
        }
        return first to headers
    }

    private class Target(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String
    )

    /** Разбор абсолютного URI (http://h[:p]/path) либо относительного + Host. */
    private fun parseTarget(target: String, headers: List<String>): Target? {
        if (target.startsWith("http://")) {
            val rest = target.substring(7)
            val slash = rest.indexOf('/')
            val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else "/"
            val colon = hostPort.indexOf(':')
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull() ?: 80 else 80
            if (host.isEmpty()) return null
            return Target("http", host, port, path)
        }
        if (target.startsWith("https://")) {
            // редкий случай: форвард на 443 как обычный запрос (обычно идёт через CONNECT)
            val rest = target.substring(8)
            val slash = rest.indexOf('/')
            val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else "/"
            val colon = hostPort.indexOf(':')
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull() ?: 443 else 443
            if (host.isEmpty()) return null
            return Target("https", host, port, path)
        }
        // относительный путь — берём Host из заголовков
        val hostHeader = headerValue(headers, "host") ?: return null
        val colon = hostHeader.indexOf(':')
        val host = if (colon >= 0) hostHeader.substring(0, colon) else hostHeader
        val port = if (colon >= 0) hostHeader.substring(colon + 1).toIntOrNull() ?: 80 else 80
        if (host.isEmpty()) return null
        return Target("http", host, port, target)
    }

    private fun headerValue(headers: List<String>, name: String): String? {
        val p = "$name:"
        for (h in headers) {
            if (h.lowercase().startsWith(p)) return h.substring(p.length).trim()
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        var b = input.read()
        if (b < 0) return null
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) bos.write(b)
            b = input.read()
            if (bos.size() > 16_384) break
        }
        if (bos.size() == 0 && b < 0) return null
        return String(bos.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun copyN(src: InputStream, dst: OutputStream, n: Int) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = src.read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            dst.write(buf, 0, r)
            left -= r
        }
    }

    private fun copyUntilEof(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val r = src.read(buf)
            if (r < 0) break
            dst.write(buf, 0, r)
        }
    }

    private fun relay(a: Socket, b: Socket) {
        val t = Thread({
            try {
                copyUntilEof(a.getInputStream(), b.getOutputStream())
                b.shutdownOutput()
            } catch (e: Exception) { /* ignore */ }
        }, "glass-relay-a")
        t.isDaemon = true
        t.start()
        try {
            copyUntilEof(b.getInputStream(), a.getOutputStream())
            a.shutdownOutput()
        } catch (e: Exception) { /* ignore */ }
        try { a.close() } catch (e: Exception) { /* ignore */ }
        try { b.close() } catch (e: Exception) { /* ignore */ }
        try { t.join(1000) } catch (e: Exception) { /* ignore */ }
    }

    private fun badRequest() =
        "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()

    private fun badGateway() =
        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()
}
