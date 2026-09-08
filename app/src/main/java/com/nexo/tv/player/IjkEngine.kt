package com.nexo.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import java.util.concurrent.Executors

/**
 * Motor multimedia IjkPlayer (el mismo motor utilizado por Tele Latino).
 *
 * Características principales:
 * - Aceleración por hardware mediante MediaCodec (sin recalentamiento del procesador).
 * - Desmultiplexión nativa con FFmpeg (compatible con flujos IPTV en vivo MPEG-TS y HLS).
 * - Zapping instantáneo con latencia cero y sin buffering de paquetes.
 * - Manejo robusto de aspecto (Pantalla completa, Zoom, 16:9, 4:3, Original).
 */
class IjkEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: IjkMediaPlayer? = null
    private var layout: IjkVideoLayout? = null
    private var currentHolder: SurfaceHolder? = null

    var onPlaying: (() -> Unit)? = null
    var onError: (() -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    private var pending: Runnable? = null
    private var seekFlush: Runnable? = null
    private var pendingSeekMs: Long? = null

    private var gen = 0
    private var released = false
    private var lastUrl: String? = null
    private var lastOpenAt = 0L
    private var endedFiredForUrl: String? = null

    private val releaseExecutor = Executors.newSingleThreadExecutor()

    enum class AspectMode { FILL, ZOOM, RATIO_16_9, RATIO_4_3, ORIGINAL }
    private var aspectMode = AspectMode.FILL

    init {
        try {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
        } catch (e: Throwable) {
            Log.e(TAG, "Error cargando librerias nativas de IjkPlayer", e)
        }
    }

    fun attach(view: IjkVideoLayout) {
        if (released) return
        layout = view
        view.setAspectMode(aspectMode)
        view.bindEngine(this)
    }

    fun onSurfaceCreated(holder: SurfaceHolder) {
        currentHolder = holder
        try {
            player?.setDisplay(holder)
        } catch (e: Throwable) {
            Log.w(TAG, "setDisplay falló", e)
        }
    }

    fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {
        currentHolder = holder
    }

    fun onSurfaceDestroyed() {
        currentHolder = null
        try {
            player?.setDisplay(null)
        } catch (_: Throwable) {}
    }

    fun playNow(url: String) = schedule(url, vod = false)

    fun playVod(url: String) = schedule(url, vod = true)

    fun playZap(url: String) = schedule(url, vod = false)

    fun togglePause() {
        if (released) return
        try {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.start()
        } catch (_: Throwable) {}
    }

    fun pause() {
        if (released) return
        try { player?.pause() } catch (_: Throwable) {}
    }

    fun resume() {
        if (released) return
        try { player?.start() } catch (_: Throwable) {}
    }

    val isPlaying: Boolean
        get() = try { !released && (player?.isPlaying == true) } catch (_: Throwable) { false }

    fun timeMs(): Long = try {
        if (released) 0L else (player?.currentPosition ?: 0L).coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }

    fun lengthMs(): Long = try {
        if (released) 0L else (player?.duration ?: 0L).coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }

    fun seekBy(deltaMs: Long) {
        if (released) return
        try {
            val len = lengthMs()
            val cur = pendingSeekMs ?: timeMs()
            val target = if (len > 0) {
                (cur + deltaMs).coerceIn(0L, len)
            } else {
                (cur + deltaMs).coerceAtLeast(0L)
            }
            pendingSeekMs = target
            seekFlush?.let { main.removeCallbacks(it) }
            val flush = Runnable {
                val t = pendingSeekMs ?: return@Runnable
                pendingSeekMs = null
                applySeek(t)
            }
            seekFlush = flush
            main.postDelayed(flush, 180L)
        } catch (_: Throwable) {}
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        try {
            val len = lengthMs()
            val target = if (len > 0) positionMs.coerceIn(0L, len) else positionMs.coerceAtLeast(0L)
            pendingSeekMs = null
            seekFlush?.let { main.removeCallbacks(it) }
            applySeek(target)
        } catch (_: Throwable) {}
    }

    private fun applySeek(positionMs: Long) {
        if (released) return
        try {
            player?.seekTo(positionMs)
        } catch (e: Throwable) {
            Log.w(TAG, "seek falló", e)
        }
    }

    data class Track(val id: Int, val name: String)

    fun audioTracks(): List<Track> {
        if (released) return emptyList()
        return try {
            val tracks = player?.trackInfo ?: return emptyList()
            tracks.mapIndexedNotNull { index, t ->
                if (t.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    val lang = t.language?.takeIf { it.isNotBlank() && it != "und" }
                    val info = t.infoInline?.takeIf { it.isNotBlank() }
                    val label = lang ?: info ?: "Audio ${index + 1}"
                    Track(index, label)
                } else null
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun currentAudioTrackId(): Int = try {
        if (released) -1 else player?.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun setAudioTrack(id: Int) {
        if (released || id < 0) return
        try {
            player?.selectTrack(id)
        } catch (_: Throwable) {}
    }

    fun cycleAudioTrack(): String? {
        val tracks = audioTracks()
        if (tracks.isEmpty()) return null
        val cur = currentAudioTrackId()
        val idx = tracks.indexOfFirst { it.id == cur }.let { if (it < 0) 0 else (it + 1) % tracks.size }
        setAudioTrack(tracks[idx].id)
        return tracks[idx].name
    }

    fun subtitleTracks(): List<Track> {
        if (released) return emptyList()
        return try {
            val tracks = player?.trackInfo ?: return emptyList()
            buildList {
                add(Track(-1, "Sin subtítulos"))
                tracks.forEachIndexed { index, t ->
                    if (t.trackType == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ||
                        t.trackType == ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                    ) {
                        val lang = t.language?.takeIf { it.isNotBlank() && it != "und" }
                        val info = t.infoInline?.takeIf { it.isNotBlank() }
                        val label = lang ?: info ?: "Sub ${index + 1}"
                        add(Track(index, label))
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun currentSubtitleTrackId(): Int = try {
        if (released) -1 else {
            val timed = player?.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) ?: -1
            if (timed >= 0) timed else player?.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) ?: -1
        }
    } catch (_: Throwable) {
        -1
    }

    fun setSubtitleTrack(id: Int) {
        if (released || id < 0) return
        try {
            if (id < 0) {
                val cur = currentSubtitleTrackId()
                if (cur >= 0) player?.deselectTrack(cur)
            } else {
                player?.selectTrack(id)
            }
        } catch (_: Throwable) {}
    }

    fun cycleSubtitleTrack(): String? {
        val tracks = subtitleTracks()
        if (tracks.isEmpty()) return "Sin subtítulos"
        val cur = currentSubtitleTrackId()
        val idx = tracks.indexOfFirst { it.id == cur }.let { if (it < 0) 0 else (it + 1) % tracks.size }
        setSubtitleTrack(tracks[idx].id)
        return tracks[idx].name
    }

    fun cycleAspectMode(): String {
        aspectMode = when (aspectMode) {
            AspectMode.FILL -> AspectMode.ZOOM
            AspectMode.ZOOM -> AspectMode.RATIO_16_9
            AspectMode.RATIO_16_9 -> AspectMode.RATIO_4_3
            AspectMode.RATIO_4_3 -> AspectMode.ORIGINAL
            AspectMode.ORIGINAL -> AspectMode.FILL
        }
        layout?.setAspectMode(aspectMode)
        return when (aspectMode) {
            AspectMode.FILL -> "Pantalla completa"
            AspectMode.ZOOM -> "Zoom"
            AspectMode.RATIO_16_9 -> "16:9"
            AspectMode.RATIO_4_3 -> "4:3"
            AspectMode.ORIGINAL -> "Original"
        }
    }

    private fun schedule(url: String, vod: Boolean) {
        if (released || url.isBlank()) return
        if (url == lastUrl && isPlayingSafe()) return
        lastUrl = url
        endedFiredForUrl = null
        val myGen = ++gen
        pending?.let { main.removeCallbacks(it) }

        // Si ya estamos en el hilo principal, ejecutamos inmediatamente sin encolar
        if (Looper.myLooper() == Looper.getMainLooper()) {
            openMedia(url, vod)
        } else {
            val r = Runnable {
                if (released || myGen != gen) return@Runnable
                openMedia(url, vod)
            }
            pending = r
            main.post(r)
        }
    }

    private fun isPlayingSafe(): Boolean = try {
        !released && (player?.isPlaying == true)
    } catch (_: Throwable) {
        false
    }

    private fun openMedia(url: String, vod: Boolean) {
        if (released) return
        lastOpenAt = SystemClock.uptimeMillis()
        onBuffering?.invoke(true)

        // Cancelar inmediatamente la descarga del stream previo para liberar 100% el ancho de banda
        StreamBridge.cancelActive()

        val oldPlayer = player
        val p = createConfiguredPlayer(vod)
        player = p

        // Desvincular de inmediato del surface y liberar el reproductor anterior en hilo secundario
        if (oldPlayer != null) {
            try { oldPlayer.setDisplay(null) } catch (_: Throwable) {}
            releaseExecutor.execute {
                try {
                    oldPlayer.stop()
                    oldPlayer.release()
                } catch (_: Throwable) {}
            }
        }

        try {
            currentHolder?.let { p.setDisplay(it) }
            val playUrl = StreamBridge.maybeWrap(url)
            p.dataSource = playUrl
            p.prepareAsync()
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al abrir media: $url", e)
            onError?.invoke()
        }
    }

    private fun createConfiguredPlayer(vod: Boolean): IjkMediaPlayer {
        val p = IjkMediaPlayer()

        // Decodificación acelerada por hardware MediaCodec (GPU de TV Box, teléfono y tablet)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L)

        // Optimización de zapping instantáneo y ultra baja latencia
        if (!vod) {
            // Cero buffering de paquetes para zapping instantáneo
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 60L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2L)

            // Detección ultrarrápida de cabecera TS (30ms / 32KB)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 30000L) // 30ms
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 32768L)      // 32KB
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 0L)

            // Codec: omitir bucle de filtrado no referencial para renderizar el primer cuadro de inmediato
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        } else {
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 500000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512000L)
        }

        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "fast", 1L)

        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 6000000L)

        p.setOnPreparedListener { mp ->
            main.post {
                if (released) return@post
                try { mp.start() } catch (_: Throwable) {}
                onBuffering?.invoke(false)
                onPlaying?.invoke()
            }
        }

        p.setOnInfoListener { _, what, _ ->
            main.post {
                if (released) return@post
                when (what) {
                    IMediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering?.invoke(true)
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> onBuffering?.invoke(false)
                    IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        onBuffering?.invoke(false)
                        onPlaying?.invoke()
                    }
                }
            }
            true
        }

        p.setOnBufferingUpdateListener { _, percent ->
            main.post {
                if (released) return@post
                onBuffering?.invoke(percent < 90)
            }
        }

        p.setOnCompletionListener {
            main.post {
                if (released) return@post
                val url = lastUrl
                if (url != null && endedFiredForUrl != url) {
                    endedFiredForUrl = url
                    onEnded?.invoke()
                }
            }
        }

        p.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Error de reproductor Ijk: what=$what extra=$extra")
            main.post {
                if (released) return@post
                onError?.invoke()
            }
            true
        }

        p.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            main.post {
                if (released) return@post
                if (width > 0 && height > 0) {
                    layout?.setVideoSize(width, height)
                }
            }
        }

        return p
    }

    fun release() {
        if (released) return
        released = true
        pending?.let { main.removeCallbacks(it) }
        seekFlush?.let { main.removeCallbacks(it) }
        layout = null
        currentHolder = null
        val p = player
        player = null
        releaseExecutor.execute {
            try {
                p?.stop()
                p?.setDisplay(null)
                p?.release()
            } catch (_: Throwable) {}
        }
        releaseExecutor.shutdown()
    }

    companion object {
        private const val TAG = "IjkEngine"
    }
}
