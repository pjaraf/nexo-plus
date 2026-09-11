package com.nexo.tv.player

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor VLC optimizado para TV Box / telefono / tablet.
 * Al zapear: corta red al instante, silencia y cambia media sin stop() bloqueante.
 */
class VlcEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var layout: VlcVideoLayout? = null
    private var viewsAttached = false

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
    private var lastWasVod = false
    private var hostRestoreSeekMs: Long = 0L
    private var pendingHostRestoreSeek: Long = 0L
    private var lastOpenAt = 0L
    private var endedFiredForUrl: String? = null
    private var mediaGen = 0
    /** Ignorar errores de VLC mientras se mata el canal anterior. */
    private val switching = AtomicBoolean(false)

    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lowRam: Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }.getOrDefault(false)

    enum class AspectMode { FILL, ZOOM, RATIO_16_9, RATIO_4_3, ORIGINAL }
    private var aspectMode = AspectMode.FILL

    init {
        try {
            libVLC = SharedLibVlc.acquire(context.applicationContext, lowRam)
        } catch (e: Throwable) {
            Log.e(TAG, "Error creando LibVLC", e)
        }
    }

    fun attach(view: VlcVideoLayout) {
        if (released) return
        // Nuevo host (Compose recreo el AndroidView): hay que reenganchar el vout.
        if (layout !== view) {
            detachVout()
            layout = view
        } else if (viewsAttached && player != null) {
            return
        } else {
            layout = view
        }
        view.setAspectMode(aspectMode)
        val mustRestoreVideo = !viewsAttached
        view.post {
            if (released || layout !== view) return@post
            ensurePlayer()
            val ok = attachVout()
            Log.i(TAG, "attach host ok=$ok attached=$viewsAttached restore=$mustRestoreVideo")
            val hasMedia = try { player?.media != null } catch (_: Throwable) { false }
            val url = lastUrl
            // VOD (fullscreen movil): conservar media/posicion.
            // Live: reabrir siempre al restaurar surface (stream frio tras pelicula/serie).
            if (hasMedia && lastWasVod) {
                try {
                    if (player?.isPlaying != true) player?.play()
                } catch (_: Throwable) {}
                return@post
            }
            if (url != null) {
                val resumeAt = if (lastWasVod) hostRestoreSeekMs else 0L
                hostRestoreSeekMs = 0L
                val vod = lastWasVod
                lastUrl = null
                if (resumeAt > 800L) pendingHostRestoreSeek = resumeAt
                schedule(url, vod = vod, debounceMs = 0L)
            } else if (hasMedia) {
                try {
                    if (player?.isPlaying != true) player?.play()
                } catch (_: Throwable) {}
            }
        }
    }

    /** Llamar cuando Compose destruye el AndroidView (cambio de tab / fullscreen). */
    fun onHostReleased(view: VlcVideoLayout) {
        if (layout !== view) return
        try {
            if (lastWasVod) {
                val t = player?.time ?: 0L
                if (t > 800L) hostRestoreSeekMs = t
            }
        } catch (_: Throwable) {}
        try {
            player?.pause()
        } catch (_: Throwable) {}
        detachVout()
        layout = null
    }

    fun playNow(url: String) = switchLive(url, debounceMs = 0L)

    fun playVod(url: String) = schedule(url, vod = true, debounceMs = 0L)

    /** Zapping: mata red YA, silencia, y abre solo el ultimo canal. */
    fun playZap(url: String) = switchLive(url, debounceMs = ZAP_DEBOUNCE_MS)

    fun playRecent(url: String) = switchLive(url, debounceMs = 0L)

    private fun switchLive(url: String, debounceMs: Long) {
        if (released || url.isBlank()) return
        switching.set(true)
        // 1) Corta descargas/sockets del canal anterior YA (no espera a VLC).
        StreamBridge.beginLiveSession()
        // 2) Silencio inmediato (sin stop() bloqueante en el hilo UI).
        softMute()
        // 3) Cancela aperturas pendientes; solo el ultimo zap gana.
        pending?.let { main.removeCallbacks(it) }
        lastUrl = null
        schedule(url, vod = false, debounceMs = debounceMs)
    }

    /** Silencia sin stop() — stop() de VLC puede congelar la UI en TV Box. */
    private fun softMute() {
        try {
            player?.volume = 0
        } catch (_: Throwable) {}
    }

    fun togglePause() {
        if (released) return
        try {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        } catch (_: Throwable) {}
    }

    fun pause() {
        if (released) return
        try { player?.pause() } catch (_: Throwable) {}
    }

    fun resume() {
        if (released) return
        try { player?.play() } catch (_: Throwable) {}
    }

    val isPlaying: Boolean
        get() = try { !released && (player?.isPlaying == true) } catch (_: Throwable) { false }

    fun timeMs(): Long = try {
        if (released) 0L else (player?.time ?: 0L).coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }

    fun lengthMs(): Long = try {
        if (released) 0L else (player?.length ?: 0L).coerceAtLeast(0L)
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
            player?.time = positionMs
        } catch (e: Throwable) {
            Log.w(TAG, "seek fallo", e)
        }
    }

    data class Track(val id: Int, val name: String)

    fun audioTracks(): List<Track> {
        if (released) return emptyList()
        return try {
            player?.audioTracks?.map { Track(it.id, it.name ?: "Audio ${it.id}") }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun currentAudioTrackId(): Int = try {
        if (released) -1 else player?.audioTrack ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun setAudioTrack(id: Int) {
        if (released || id < 0) return
        try {
            player?.audioTrack = id
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
            buildList {
                add(Track(-1, "Sin subtitulos"))
                player?.spuTracks?.forEach { t ->
                    add(Track(t.id, t.name ?: "Sub ${t.id}"))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun currentSubtitleTrackId(): Int = try {
        if (released) -1 else player?.spuTrack ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun setSubtitleTrack(id: Int) {
        if (released) return
        try {
            player?.spuTrack = id
        } catch (_: Throwable) {}
    }

    fun cycleSubtitleTrack(): String? {
        val tracks = subtitleTracks()
        if (tracks.isEmpty()) return "Sin subtitulos"
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
        applyVlcAspect()
        return when (aspectMode) {
            AspectMode.FILL -> "Pantalla completa"
            AspectMode.ZOOM -> "Zoom"
            AspectMode.RATIO_16_9 -> "16:9"
            AspectMode.RATIO_4_3 -> "4:3"
            AspectMode.ORIGINAL -> "Original"
        }
    }

    private fun applyVlcAspect() {
        val p = player ?: return
        try {
            when (aspectMode) {
                AspectMode.RATIO_16_9 -> {
                    p.aspectRatio = "16:9"
                    p.scale = 0f
                }
                AspectMode.RATIO_4_3 -> {
                    p.aspectRatio = "4:3"
                    p.scale = 0f
                }
                else -> {
                    p.aspectRatio = null
                    p.scale = 0f
                }
            }
        } catch (_: Throwable) {}
    }

    private fun schedule(url: String, vod: Boolean, debounceMs: Long = 0L) {
        if (released || url.isBlank()) return
        if (url == lastUrl && isPlayingSafe() && !switching.get()) return
        lastUrl = url
        lastWasVod = vod
        endedFiredForUrl = null
        val myGen = ++gen
        pending?.let { main.removeCallbacks(it) }

        val r = Runnable {
            if (released || myGen != gen) return@Runnable
            openMedia(url, vod, myGen)
        }
        pending = r
        if (debounceMs <= 0L && Looper.myLooper() == Looper.getMainLooper()) {
            r.run()
        } else {
            main.postDelayed(r, debounceMs.coerceAtLeast(0L))
        }
    }

    private fun isPlayingSafe(): Boolean = try {
        !released && (player?.isPlaying == true)
    } catch (_: Throwable) {
        false
    }

    private fun ensurePlayer(): MediaPlayer? {
        if (released) return null
        val lib = libVLC ?: return null
        val existing = player
        if (existing != null) return existing

        val p = MediaPlayer(lib)
        player = p

        p.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    main.post {
                        if (released || mediaGen != gen) return@post
                        switching.set(false)
                        val seek = pendingHostRestoreSeek
                        if (seek > 800L) {
                            pendingHostRestoreSeek = 0L
                            try {
                                player?.time = seek
                            } catch (_: Throwable) {}
                        }
                        ensureAudible()
                        onBuffering?.invoke(false)
                        onPlaying?.invoke()
                        refreshVideoSize()
                    }
                }
                MediaPlayer.Event.Buffering -> {
                    if (switching.get()) return@setEventListener
                    val buffering = event.buffering < 100f
                    main.post {
                        if (released || mediaGen != gen) return@post
                        onBuffering?.invoke(buffering)
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    main.post {
                        if (released || mediaGen != gen || lastWasVod.not()) return@post
                        val url = lastUrl
                        if (url != null && endedFiredForUrl != url) {
                            endedFiredForUrl = url
                            onEnded?.invoke()
                        }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    // Errores al matar el canal anterior: ignorar (no sacar de fullscreen / mantenimiento).
                    if (switching.get() || mediaGen != gen) return@setEventListener
                    val age = SystemClock.uptimeMillis() - lastOpenAt
                    if (age < 500L) return@setEventListener
                    Log.e(TAG, "Error VLC")
                    main.post {
                        if (released || mediaGen != gen || switching.get()) return@post
                        onError?.invoke()
                    }
                }
                MediaPlayer.Event.Vout -> {
                    main.post {
                        if (released || mediaGen != gen) return@post
                        switching.set(false)
                        refreshVideoSize()
                        ensureAudible()
                        onBuffering?.invoke(false)
                        onPlaying?.invoke()
                    }
                }
                MediaPlayer.Event.ESAdded -> {
                    main.post {
                        if (released || mediaGen != gen) return@post
                        refreshVideoSize()
                    }
                }
            }
        }

        attachVout()
        return p
    }

    private fun openMedia(url: String, vod: Boolean, openGen: Int) {
        if (released || openGen != gen) return
        mediaGen = openGen
        lastOpenAt = SystemClock.uptimeMillis()
        if (!vod) switching.set(true)

        val lib = libVLC ?: run {
            switching.set(false)
            onError?.invoke()
            return
        }
        val p = ensurePlayer() ?: run {
            switching.set(false)
            onError?.invoke()
            return
        }

        try {
            attachVout()

            val playUrl = StreamBridge.maybeWrap(url)
            val media = Media(lib, Uri.parse(playUrl))
            media.setHWDecoderEnabled(true, false)
            val cache = if (lowRam) 700 else if (vod) 1400 else 800
            media.addOption(":network-caching=$cache")
            if (vod) {
                media.addOption(":file-caching=$cache")
            } else {
                media.addOption(":live-caching=$cache")
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")
            }
            media.addOption(":http-reconnect")

            // setMedia reemplaza el anterior sin stop() bloqueante en UI.
            val previous = p.media
            p.media = media
            media.release()
            try {
                previous?.release()
            } catch (_: Throwable) {}

            requestAudioFocus()
            applyVlcAspect()
            p.volume = 100
            p.play()
            Log.i(TAG, "play lowRam=$lowRam $playUrl")
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al abrir media: $url", e)
            switching.set(false)
            onError?.invoke()
        }
    }

    private fun attachVout(): Boolean {
        val p = player ?: return false
        val host = layout ?: return false
        val vlcLayout = host.vlcLayout
        if (!host.isAttachedToWindow) return false
        try {
            if (viewsAttached) return true
            p.attachViews(vlcLayout, null, false, false)
            viewsAttached = true
            Log.i(TAG, "attachViews OK ${host.width}x${host.height}")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "attachViews fallo; reintento", e)
            try { p.detachViews() } catch (_: Throwable) {}
            viewsAttached = false
            return try {
                p.attachViews(vlcLayout, null, false, false)
                viewsAttached = true
                true
            } catch (e2: Throwable) {
                Log.w(TAG, "attachViews reintento fallo", e2)
                false
            }
        }
    }

    private fun detachVout() {
        val p = player ?: run {
            viewsAttached = false
            return
        }
        try {
            if (viewsAttached) p.detachViews()
        } catch (_: Throwable) {}
        viewsAttached = false
    }

    private fun refreshVideoSize() {
        try {
            val track = player?.currentVideoTrack ?: return
            val w = track.width
            val h = track.height
            if (w > 0 && h > 0) {
                layout?.setVideoSize(w, h)
            }
        } catch (_: Throwable) {}
    }

    private fun requestAudioFocus() {
        try {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        } catch (_: Throwable) {}
    }

    private fun ensureAudible() {
        requestAudioFocus()
        try {
            player?.volume = 100
        } catch (_: Throwable) {}
        try {
            val tracks = audioTracks()
            val selected = currentAudioTrackId()
            if (tracks.isEmpty()) return
            if (selected < 0 || tracks.none { it.id == selected }) {
                setAudioTrack(tracks.first().id)
            }
        } catch (_: Throwable) {}
    }

    fun release() {
        if (released) return
        released = true
        switching.set(false)
        pending?.let { main.removeCallbacks(it) }
        seekFlush?.let { main.removeCallbacks(it) }
        val p = player
        player = null
        val lib = libVLC
        libVLC = null
        val attached = viewsAttached
        viewsAttached = false
        layout = null
        val exec = releaseExecutor
        // Importante: NO hacer shutdown() antes del execute() (crash RejectedExecutionException).
        main.post {
            try {
                if (attached) p?.detachViews()
            } catch (_: Throwable) {}
            val work = Runnable {
                try {
                    p?.stop()
                    p?.media?.release()
                    p?.release()
                } catch (_: Throwable) {}
                try {
                    SharedLibVlc.release()
                } catch (_: Throwable) {}
                try {
                    exec.shutdown()
                } catch (_: Throwable) {}
            }
            try {
                exec.execute(work)
            } catch (_: Throwable) {
                try {
                    work.run()
                } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "VlcEngine"
        /** Debounce corto: solo el ultimo canal abre; la red ya se mato al instante. */
        private const val ZAP_DEBOUNCE_MS = 90L

        private fun defaultOptions(lowRam: Boolean): ArrayList<String> {
            val cache = if (lowRam) "700" else "900"
            return arrayListOf(
                "--aout=opensles",
                "--audio-time-stretch",
                "--avcodec-skiploopfilter=${if (lowRam) 4 else 1}",
                "--drop-late-frames",
                "--skip-frames",
                "--network-caching=$cache",
                "--live-caching=$cache",
                "--http-reconnect"
            )
        }
    }
}

/**
 * Un solo LibVLC por proceso (:player). Si pelicula/serie liberan el suyo,
 * el live de LiveActivity se cae y la app se cierra.
 */
private object SharedLibVlc {
    private val lock = Any()
    private var lib: LibVLC? = null
    private var refs = 0

    fun acquire(appContext: Context, lowRam: Boolean): LibVLC {
        synchronized(lock) {
            val existing = lib
            if (existing != null) {
                refs++
                return existing
            }
            val created = LibVLC(appContext, VlcEngineDefaultOptions.create(lowRam))
            lib = created
            refs = 1
            return created
        }
    }

    fun release() {
        synchronized(lock) {
            if (refs <= 0) return
            refs--
            if (refs <= 0) {
                try {
                    lib?.release()
                } catch (_: Throwable) {}
                lib = null
                refs = 0
            }
        }
    }
}

private object VlcEngineDefaultOptions {
    fun create(lowRam: Boolean): ArrayList<String> {
        val cache = if (lowRam) "700" else "900"
        return arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=${if (lowRam) 4 else 1}",
            "--drop-late-frames",
            "--skip-frames",
            "--network-caching=$cache",
            "--live-caching=$cache",
            "--http-reconnect"
        )
    }
}
