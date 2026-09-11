package com.nexo.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
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
 * Caracteristicas principales:
 * - Aceleracion por hardware mediante MediaCodec (sin recalentamiento del procesador).
 * - Desmultiplexion nativa con FFmpeg (compatible con flujos IPTV en vivo MPEG-TS y HLS).
 * - Zapping instantaneo con latencia cero y sin buffering de paquetes.
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
    private var audioRescueTriedForUrl: String? = null
    private var audioCheck: Runnable? = null
    /** 0 = AudioTrack, 1 = OpenSLES */
    private var liveAudioBackend = 1
    /** 0 = decode audio por software, 1 = mediacodec-audio (AC3/EAC3 por HDMI). */
    private var liveMediacodecAudio = 0
    private var audioRescueStep = 0

    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
            Log.w(TAG, "setDisplay fallo", e)
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

    fun playNow(url: String) = switchLive(url, debounceMs = 0L)

    fun playVod(url: String) = schedule(url, vod = true, debounceMs = 0L)

    /** Zapping rápido: mata streams anteriores al instante y abre el canal tras un debounce corto. */
    fun playZap(url: String) = switchLive(url, debounceMs = ZAP_DEBOUNCE_MS)

    /** Canal reciente al volver atrás: mata anteriores y abre sin esperar. */
    fun playRecent(url: String) = switchLive(url, debounceMs = 0L)

    private fun switchLive(url: String, debounceMs: Long) {
        if (released || url.isBlank()) return
        // Mata de inmediato descargas/sockets de canales anteriores
        StreamBridge.beginLiveSession()
        // Suelta el reproductor actual ya (deja de decodificar el canal viejo)
        killCurrentPlayer()
        lastUrl = null // forzar reopen aunque sea la misma URL
        audioRescueTriedForUrl = null
        audioRescueStep = 0
        liveAudioBackend = 1
        liveMediacodecAudio = 0
        schedule(url, vod = false, debounceMs = debounceMs)
    }

    private fun killCurrentPlayer() {
        val old = player ?: return
        player = null
        try { old.setDisplay(null) } catch (_: Throwable) {}
        releaseExecutor.execute {
            try { old.stop() } catch (_: Throwable) {}
            try { old.release() } catch (_: Throwable) {}
        }
    }

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
            Log.w(TAG, "seek fallo", e)
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
                add(Track(-1, "Sin subtitulos"))
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
        return when (aspectMode) {
            AspectMode.FILL -> "Pantalla completa"
            AspectMode.ZOOM -> "Zoom"
            AspectMode.RATIO_16_9 -> "16:9"
            AspectMode.RATIO_4_3 -> "4:3"
            AspectMode.ORIGINAL -> "Original"
        }
    }

    private fun schedule(url: String, vod: Boolean, debounceMs: Long = 0L) {
        if (released || url.isBlank()) return
        if (url == lastUrl && isPlayingSafe()) return
        lastUrl = url
        endedFiredForUrl = null
        val myGen = ++gen
        pending?.let { main.removeCallbacks(it) }

        val r = Runnable {
            if (released || myGen != gen) return@Runnable
            openMedia(url, vod)
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

    private fun openMedia(url: String, vod: Boolean) {
        if (released) return
        lastOpenAt = SystemClock.uptimeMillis()
        onBuffering?.invoke(true)

        // Por si quedó un reproductor de un zap intermedio
        killCurrentPlayer()

        val p = createConfiguredPlayer(vod)
        player = p

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

        // Decodificacion acelerada por hardware MediaCodec (GPU de TV Box, telefono y tablet)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L)

        // Live: priorizar descubrir y mantener la pista de audio (muchos TS traen audio tarde).
        if (!vod) {
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 60L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 8L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 3000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L)
            // Probe amplio: probesize bajo dejaba video OK y audio ausente.
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2_000_000L) // 2s
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1_572_864L)       // 1.5MB
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "scan_all_pmts", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 500_000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        } else {
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 500000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512000L)
        }

        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
        val useOpenSles = !vod && liveAudioBackend == 1
        val useMcAudio = !vod && liveMediacodecAudio == 1
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", if (useOpenSles) 1L else 0L)
        // Algunos nacionales (AC3/EAC3) solo suenan con mediacodec-audio hacia HDMI.
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-audio", if (useMcAudio) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "fast", 1L)

        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 6000000L)

        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {}

        val openGen = gen

        p.setOnPreparedListener { mp ->
            main.post {
                if (released || openGen != gen || player !== p) return@post
                ensureAudible(mp)
                try { mp.start() } catch (_: Throwable) {}
                scheduleAudioRescue(openGen)
                onBuffering?.invoke(false)
                onPlaying?.invoke()
            }
        }

        p.setOnInfoListener { _, what, _ ->
            main.post {
                if (released || openGen != gen || player !== p) return@post
                when (what) {
                    IMediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering?.invoke(true)
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> onBuffering?.invoke(false)
                    IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        ensureAudible(p)
                        scheduleAudioRescue(openGen)
                        onBuffering?.invoke(false)
                        onPlaying?.invoke()
                    }
                }
            }
            true
        }

        // Live: no spamear main con % de buffer (MEDIA_INFO_BUFFERING_* alcanza).
        if (vod) {
            p.setOnBufferingUpdateListener { _, percent ->
                main.post {
                    if (released || openGen != gen || player !== p) return@post
                    onBuffering?.invoke(percent < 90)
                }
            }
        }

        p.setOnCompletionListener {
            main.post {
                if (released || openGen != gen || player !== p) return@post
                val url = lastUrl
                if (url != null && endedFiredForUrl != url) {
                    endedFiredForUrl = url
                    onEnded?.invoke()
                }
            }
        }

        p.setOnErrorListener { _, what, extra ->
            // Ignorar errores de reproductores ya descartados al cambiar de canal
            if (released || openGen != gen) return@setOnErrorListener true
            Log.e(TAG, "Error de reproductor Ijk: what=$what extra=$extra")
            main.post {
                if (released || openGen != gen || player !== p) return@post
                onError?.invoke()
            }
            true
        }

        p.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            main.post {
                if (released || openGen != gen || player !== p) return@post
                if (width > 0 && height > 0) {
                    layout?.setVideoSize(width, height)
                }
            }
        }

        return p
    }

    private fun ensureAudible(mp: IMediaPlayer) {
        try {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        } catch (_: Throwable) {}
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0 && cur <= 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (max * 0.6f).toInt().coerceAtLeast(1),
                    0
                )
            }
        } catch (_: Throwable) {}
        try {
            mp.setVolume(1f, 1f)
        } catch (_: Throwable) {}
        try {
            val ijk = mp as? IjkMediaPlayer ?: return
            val tracks = ijk.trackInfo
            val audioIdx = tracks?.mapIndexedNotNull { index, t ->
                if (t.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) index else null
            }.orEmpty()
            val selected = runCatching {
                ijk.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)
            }.getOrDefault(-1)
            Log.i(
                TAG,
                "audioTracks=${audioIdx.size} backend=$liveAudioBackend mcAudio=$liveMediacodecAudio selected=$selected"
            )
            if (audioIdx.isEmpty()) return
            if (selected < 0 || selected !in audioIdx) {
                ijk.selectTrack(audioIdx.first())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ensureAudible track select", e)
        }
    }

    /** true si el sistema está sacando samples de media (no HDMI en standby). */
    private fun isMediaAudible(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.activePlaybackConfigurations.any { cfg ->
                    val usage = cfg.audioAttributes.usage
                    usage == AudioAttributes.USAGE_MEDIA ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isMusicActive
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Video sin sonido real: reabre el MISMO canal con otra ruta de audio.
     * 1) otra pista  2) AudioTrack  3) OpenSLES+mediacodec-audio  4) AudioTrack+mediacodec-audio
     */
    private fun scheduleAudioRescue(openGen: Int) {
        audioCheck?.let { main.removeCallbacks(it) }
        val url = lastUrl ?: return
        if (audioRescueStep >= 4) return
        val r = Runnable {
            if (released || openGen != gen || player == null) return@Runnable
            val p = player ?: return@Runnable
            ensureAudible(p)
            val audible = isMediaAudible()
            val tracks = audioTracks()
            val selected = currentAudioTrackId()
            Log.i(
                TAG,
                "audioRescue step=$audioRescueStep audible=$audible tracks=${tracks.size} sel=$selected backend=$liveAudioBackend mc=$liveMediacodecAudio"
            )
            if (audible) return@Runnable

            audioRescueStep++
            audioRescueTriedForUrl = url
            when (audioRescueStep) {
                1 -> {
                    if (tracks.size > 1) {
                        val next = tracks.firstOrNull { it.id != selected } ?: tracks.first()
                        Log.w(TAG, "sin audio audible; probando pista ${next.name}")
                        setAudioTrack(next.id)
                        ensureAudible(p)
                        try { p.start() } catch (_: Throwable) {}
                        // Re-chequear sin subir de paso otra vez
                        audioRescueStep = 1
                        main.postDelayed({
                            if (released || openGen != gen) return@postDelayed
                            if (!isMediaAudible()) {
                                liveAudioBackend = 0
                                liveMediacodecAudio = 0
                                Log.w(TAG, "sigue mudo; reabriendo AudioTrack software")
                                lastUrl = null
                                audioRescueStep = 2
                                schedule(url, vod = false, debounceMs = 0L)
                            }
                        }, 1800L)
                    } else {
                        liveAudioBackend = 0
                        liveMediacodecAudio = 0
                        Log.w(TAG, "sin audio audible; reabriendo AudioTrack software")
                        lastUrl = null
                        schedule(url, vod = false, debounceMs = 0L)
                    }
                }
                2 -> {
                    liveAudioBackend = 1
                    liveMediacodecAudio = 1
                    Log.w(TAG, "sin audio audible; reabriendo OpenSLES + mediacodec-audio")
                    lastUrl = null
                    schedule(url, vod = false, debounceMs = 0L)
                }
                3 -> {
                    liveAudioBackend = 0
                    liveMediacodecAudio = 1
                    Log.w(TAG, "sin audio audible; reabriendo AudioTrack + mediacodec-audio")
                    lastUrl = null
                    schedule(url, vod = false, debounceMs = 0L)
                }
                else -> {
                    Log.w(TAG, "audioRescue agotado para $url")
                }
            }
        }
        audioCheck = r
        main.postDelayed(r, 2200L)
    }

    fun release() {
        if (released) return
        released = true
        pending?.let { main.removeCallbacks(it) }
        seekFlush?.let { main.removeCallbacks(it) }
        audioCheck?.let { main.removeCallbacks(it) }
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
        /** Espera corta al zapear rápido: solo el último canal abre, matando los anteriores al instante. */
        private const val ZAP_DEBOUNCE_MS = 55L
    }
}
