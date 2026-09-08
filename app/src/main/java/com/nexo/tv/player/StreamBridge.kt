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
 * OkHttp maneja TLS 1.3 certificado y entrega el flujo a IjkPlayer por 127.0.0.1 con tcpNoDelay.
 */
object StreamBridge {
    private const val TAG = "StreamBridge"
    private val running = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private val targets = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var port: Int = 0
    private var server: ServerSocket? = null
    @Volatile private var activeCall: Call? = null

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

    fun wrap(remoteUrl: String): String {
        start()
        val id = seq.incrementAndGet().toString()
        targets[id] = remoteUrl
        return "http://127.0.0.1:$port/$id"
    }

    /** Solo HTTPS necesita el puente; HTTP se reproduce directo. */
    fun maybeWrap(remoteUrl: String): String {
        return if (remoteUrl.startsWith("https://", true)) wrap(remoteUrl) else remoteUrl
    }

    /** Cancela de inmediato cualquier descarga activa del canal anterior */
    fun cancelActive() {
        try { activeCall?.cancel() } catch (_: Throwable) {}
        activeCall = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1].substringBefore("?").trimStart('/')
            val id = path.substringBefore("/")
            val remote = targets[id]
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

            val reqBuilder = Request.Builder()
                .url(remote)
                .header("User-Agent", "IPTVSmartersPro")
                .header("Accept", "*/*")
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }

            val call = Http.client.newCall(reqBuilder.build())
            // Cancelar canal previo para liberar ancho de banda al 100%
            activeCall?.cancel()
            activeCall = call

            call.execute().use { resp ->
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
                    headers["Content-Type"] = ctype.ifBlank { "video/mp2t" }
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
                    
                    // Streaming directo a IjkPlayer con buffer optimizado de 32KB
                    val inStream = body.byteStream()
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (inStream.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
                out.flush()
            }
        } catch (e: Throwable) {
            // Cancelado intencionalmente al cambiar de canal o cerrado
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun rewritePlaylist(body: String, playlistUrl: String): String {
        val base = playlistUrl.toHttpUrlOrNull()
        val out = StringBuilder()
        body.lineSequence().forEach { line ->
            val trim = line.trim()
            if (trim.isEmpty() || trim.startsWith("#")) {
                out.append(line).append("\n")
            } else {
                val resolved = when {
                    trim.startsWith("http://", true) || trim.startsWith("https://", true) -> trim
                    base != null -> base.resolve(trim)?.toString() ?: trim
                    else -> trim
                }
                out.append(maybeWrap(resolved)).append("\n")
            }
        }
        return out.toString()
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
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (contentLength >= 0 && !headers.containsKey("Content-Length")) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }
}
