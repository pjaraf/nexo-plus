package com.nexo.tv.player

import android.util.Log
import com.nexo.tv.data.Http
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puente ultra rápido local para flujos HTTPS.
 * OkHttp maneja TLS 1.3 certificado y entrega el flujo a VLC por 127.0.0.1 con tcpNoDelay.
 *
 * Al cambiar de canal, [beginLiveSession] mata al instante todas las descargas y sockets
 * de sesiones anteriores para liberar ancho de banda de inmediato.
 */
object StreamBridge {
    private const val TAG = "StreamBridge"
    private val running = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private val sessionId = AtomicInteger(0)
    private val targets = ConcurrentHashMap<String, String>()
    private val activeCalls = ConcurrentHashMap<Call, Int>()
    private val activeSockets = ConcurrentHashMap<Socket, Int>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var port: Int = 0
    private var server: ServerSocket? = null
    @Volatile private var lastRemoteUrl: String? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val sock = ss.accept()
                    sock.tcpNoDelay = true
                    sock.sendBufferSize = 64 * 1024
                    sock.receiveBufferSize = 64 * 1024
                    pool.execute { handle(sock) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }, "nexo-bridge").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on 127.0.0.1:$port")
    }

    /**
     * Nueva sesión de canal en vivo: cancela llamadas OkHttp y cierra sockets de sesiones viejas.
     * Las conexiones de la sesión nueva no se tocan.
     */
    fun beginLiveSession(): Int {
        val next = sessionId.incrementAndGet()
        var killedCalls = 0
        var killedSocks = 0
        activeCalls.entries.removeIf { (call, sid) ->
            if (sid < next) {
                try { call.cancel() } catch (_: Throwable) {}
                killedCalls++
                true
            } else false
        }
        activeSockets.entries.removeIf { (socket, sid) ->
            if (sid < next) {
                try { socket.close() } catch (_: Throwable) {}
                killedSocks++
                true
            } else false
        }
        if (killedCalls > 0 || killedSocks > 0) {
            Log.i(TAG, "killed stale session<$next calls=$killedCalls sockets=$killedSocks")
        }
        return next
    }

    fun wrap(remoteUrl: String): String {
        start()
        val id = seq.incrementAndGet().toString()
        targets[id] = remoteUrl
        lastRemoteUrl = remoteUrl
        return "http://127.0.0.1:$port/$id"
    }

    /** Solo HTTPS necesita el puente; HTTP se reproduce directo. */
    fun maybeWrap(remoteUrl: String): String {
        return if (remoteUrl.startsWith("https://", true)) wrap(remoteUrl) else remoteUrl
    }

    private fun handle(socket: Socket) {
        val mySession = sessionId.get()
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        activeSockets[socket] = mySession
        var call: Call? = null
        try {
            if (mySession < sessionId.get()) return

            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1].trimStart('/')
            val id = rawPath.substringBefore("?").substringBefore("/")

            val remote = targets[id] ?: lastRemoteUrl?.let { base ->
                val baseHttp = base.toHttpUrlOrNull()
                baseHttp?.resolve(rawPath)?.toString()
            }

            val out = socket.getOutputStream()
            if (remote.isNullOrBlank()) {
                writeStatus(out, 404, "Not Found", emptyMap(), 0)
                return
            }

            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Range", ignoreCase = true)) rangeHeader = value
            }

            if (mySession < sessionId.get()) return

            val reqBuilder = Request.Builder()
                .url(remote)
                .header("User-Agent", "IPTVSmartersPro")
                .header("Accept", "*/*")
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }

            val built = Http.mediaClient.newCall(reqBuilder.build())
            call = built
            activeCalls[built] = mySession
            if (mySession < sessionId.get()) {
                built.cancel()
                return
            }

            built.execute().use { resp ->
                if (mySession < sessionId.get()) return
                if (!resp.isSuccessful && resp.code != 206) {
                    writeStatus(out, resp.code, "Error", emptyMap(), 0)
                    return
                }
                val body = resp.body ?: run {
                    writeStatus(out, 502, "Bad Gateway", emptyMap(), 0)
                    return
                }
                val finalUrl = resp.request.url.toString()
                val ctype = (resp.header("Content-Type") ?: body.contentType()?.toString().orEmpty()).lowercase()
                val isPlaylist = ctype.contains("mpegurl") ||
                    ctype.contains("x-mpegurl") ||
                    ctype.contains("vnd.apple") ||
                    (finalUrl.contains(".m3u8", true) && !ctype.contains("video/") && !ctype.contains("mp2t"))
                if (isPlaylist) {
                    val text = body.string()
                    if (mySession < sessionId.get()) return
                    val rewritten = rewritePlaylist(text, finalUrl)
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    writeStatus(
                        out,
                        200,
                        "OK",
                        mapOf(
                            "Content-Type" to "application/vnd.apple.mpegurl",
                            "Accept-Ranges" to "bytes"
                        ),
                        bytes.size
                    )
                    out.write(bytes)
                    out.flush()
                } else {
                    val len = body.contentLength()
                    val headers = linkedMapOf<String, String>()
                    headers["Content-Type"] = ctype.ifBlank { "application/octet-stream" }
                    headers["Accept-Ranges"] = "bytes"
                    resp.header("Content-Range")?.let { headers["Content-Range"] = it }
                    resp.header("Content-Length")?.let { headers["Content-Length"] = it }
                    val status = if (resp.code == 206) 206 else 200
                    val reason = if (status == 206) "Partial Content" else "OK"
                    val declaredLen = when {
                        headers.containsKey("Content-Length") -> headers["Content-Length"]!!.toIntOrNull() ?: -1
                        len > 0 -> len.toInt()
                        else -> -1
                    }
                    if (!headers.containsKey("Content-Length") && declaredLen >= 0) {
                        headers["Content-Length"] = declaredLen.toString()
                    }
                    writeStatus(out, status, reason, headers, if (headers.containsKey("Content-Length")) -2 else declaredLen)
                    val inputStream = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var sinceFlush = 0
                    while (true) {
                        if (mySession < sessionId.get()) break
                        val n = inputStream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sinceFlush += n
                        if (sinceFlush >= 256 * 1024) {
                            out.flush()
                            sinceFlush = 0
                        }
                    }
                    if (sinceFlush > 0) out.flush()
                }
            }
        } catch (e: Throwable) {
            // Socket/call cerrado al cambiar de canal (sesión matada)
        } finally {
            call?.let { activeCalls.remove(it) }
            activeSockets.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun rewritePlaylist(body: String, playlistUrl: String): String {
        val base = playlistUrl.toHttpUrlOrNull()
        return body.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> raw.replace(Regex("""URI="([^"]+)"""")) { m ->
                    val abs = resolve(base, m.groupValues[1])
                    """URI="${wrap(abs)}""""
                }
                else -> wrap(resolve(base, line))
            }
        }
    }

    private fun resolve(base: okhttp3.HttpUrl?, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return base?.resolve(ref)?.toString() ?: ref
    }

    private fun writeStatus(
        out: java.io.OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        contentLength: Int
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(" ").append(reason).append("\r\n")
        headers.forEach { (k, v) ->
            if (!k.equals("Content-Length", true) || contentLength == -2) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
        }
        if (contentLength >= 0 && !headers.keys.any { it.equals("Content-Length", true) }) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }
}
