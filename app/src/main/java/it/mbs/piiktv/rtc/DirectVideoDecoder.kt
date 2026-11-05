package it.mbs.piiktv.rtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.webrtc.EncodedImage
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Contatori del decoder mostrati nell'overlay statistiche. */
object DecoderStats {
    val framesIn = AtomicLong()
    val framesOut = AtomicLong()
    val framesSkipped = AtomicLong()
    val bytesIn = AtomicLong()
    val keyFrames = AtomicLong()
    @Volatile var decodeMsAvg = 0.0
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var codecName = ""
    @Volatile var lowLatency = false
    /** Richiesta manuale di keyframe (tasto SU del telecomando). */
    @Volatile var keyFrameRequest = false
    /** Ritardo accumulato stimato rispetto al flusso in diretta (ms). */
    @Volatile var lagMs = 0L
    /** Quante volte l'arretrato è stato scartato per tornare in diretta. */
    val catchUps = AtomicLong()
    val droppedForCatchUp = AtomicLong()

    fun reset() {
        framesIn.set(0); framesOut.set(0); framesSkipped.set(0); bytesIn.set(0); keyFrames.set(0)
        catchUps.set(0); droppedForCatchUp.set(0); lagMs = 0
        decodeMsAvg = 0.0; width = 0; height = 0
    }

    fun onDecodeMs(ms: Double) {
        decodeMsAvg = if (decodeMsAvg == 0.0) ms else decodeMsAvg * 0.9 + ms * 0.1
    }
}

/** Decoder hardware disponibili (MediaCodec), con le capacità utili per la bassa latenza. */
object HwCodecs {
    data class Hw(
        val name: String,
        val mime: String,
        val lowLatency: Boolean,
        val adaptive: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    private val cache = HashMap<String, Hw?>()

    /** Decoder software MediaCodec (libvpx/libgav1 di Android, multi-thread) per [mime]. */
    @Synchronized
    fun findSoftware(mime: String): Hw? {
        val key = "sw:$mime"
        if (cache.containsKey(key)) return cache[key]
        var best: Hw? = null
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || !isSoftware(info)) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = try { info.getCapabilitiesForType(mime) } catch (_: Exception) { continue }
                val vc = caps.videoCapabilities
                val hw = Hw(info.name, mime, false,
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
                    vc?.supportedWidths?.upper ?: 1920, vc?.supportedHeights?.upper ?: 1080)
                // Preferisce Codec2 (c2.android.*) al vecchio OMX.google.*
                if (best == null || info.name.startsWith("c2.")) best = hw
            }
        } catch (e: Exception) {
            Log.w("PiikDecoder", "codec list: $e")
        }
        cache[key] = best
        Log.i("PiikDecoder", "sw decoder for $mime -> $best")
        return best
    }

    @Synchronized
    fun find(mime: String): Hw? {
        if (cache.containsKey(mime)) return cache[mime]
        var best: Hw? = null
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                if (isSoftware(info)) continue
                if (info.name.endsWith(".secure")) continue
                val caps = try { info.getCapabilitiesForType(mime) } catch (_: Exception) { continue }
                val ll = Build.VERSION.SDK_INT >= 30 &&
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                val adaptive = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                val vc = caps.videoCapabilities
                val hw = Hw(
                    info.name, mime, ll, adaptive,
                    vc?.supportedWidths?.upper ?: 1920, vc?.supportedHeights?.upper ?: 1080,
                )
                if (best == null || (hw.lowLatency && !best.lowLatency)) best = hw
            }
        } catch (e: Exception) {
            Log.w("PiikDecoder", "codec list: $e")
        }
        cache[mime] = best
        Log.i("PiikDecoder", "hw decoder for $mime -> $best")
        return best
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val n = info.name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.")
    }
}

/**
 * Decoder video per libwebrtc che usa MediaCodec hardware in modalità low-latency e rende i
 * fotogrammi DIRETTAMENTE sulla Surface dello schermo (releaseOutputBuffer(render = true)):
 * niente SurfaceTexture/OpenGL in mezzo, quindi meno latenza e meno lavoro per la TV.
 *
 * A libwebrtc viene restituito un fotogramma "segnaposto" 2x2 con lo stesso timestamp, così le sue
 * statistiche e il controllo del flusso (richieste di keyframe, temporizzazione) restano coerenti.
 */
class DirectVideoDecoder(
    private val hw: HwCodecs.Hw,
    private val skipStaleFrames: Boolean,
    /** Ritardo massimo tollerato prima di scartare l'arretrato e tornare in diretta. */
    private val maxLagMs: Long = 250,
    /** Presentazione in coda sincronizzata al vsync (un fotogramma per refresh) invece che "appena pronto". */
    private val usePacer: Boolean = true,
    /** Fotogrammi di scorta del pacer (0 = bilanciata, 1 = massima fluidità). */
    private val pacerSpare: Int = 0,
    /** Refresh con scorta in eccesso prima che il pacer scarti un fotogramma per ridurre la latenza. */
    private val pacerWindow: Int = 240,
    /** Lunghezza della coda oltre la quale il pacer la considera un arretrato da scartare. */
    private val pacerBacklog: Int = 6,
) : VideoDecoder, VideoSurface.Owner {

    private val tag = "PiikDecoder"
    override val generation: Long = VideoSurface.nextGeneration()
    private val codecLock = Object()
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var callback: VideoDecoder.Callback? = null
    @Volatile private var running = false
    @Volatile private var keyFrameRequired = true
    private var outputThread: Thread? = null
    private var width = 1920
    private var height = 1080
    private val inputTimes = ConcurrentHashMap<Long, Long>()
    private val placeholder: JavaI420Buffer = JavaI420Buffer.allocate(2, 2)
    private val released = AtomicBoolean(false)
    private var decodeThreadTuned = false

    // Stima del ritardo: offset = ora locale - tempo RTP del fotogramma. Il minimo dell'offset
    // (con una lenta deriva verso l'alto per tollerare la differenza tra gli orologi) è la "diretta";
    // quanto l'offset attuale lo supera è il ritardo accumulato a valle della rete.
    private var lagBaseline = Long.MIN_VALUE
    private var lagBaselineAt = 0L
    private var catchingUp = false

    // Misure di fluidità: cadenza della sorgente (RTP) e regolarità degli arrivi.
    @Volatile private var pacer: VsyncPacer? = null
    private var lastRtpMs = Long.MIN_VALUE
    private var lastArrivalMs = 0L
    private val intervals = ArrayDeque<Long>()
    private val jitters = ArrayDeque<Double>()

    override fun getImplementationName(): String = "PiikTV-Direct(${hw.name})"

    // Nota: libwebrtc chiama questi metodi via JNI e può passare null (es. DecodeInfo): i parametri
    // devono essere nullable, altrimenti Kotlin lancia un'eccezione che fa abortire il processo.
    override fun initDecode(settings: VideoDecoder.Settings?, cb: VideoDecoder.Callback?): VideoCodecStatus {
        callback = cb
        if (settings != null && settings.width > 0 && settings.height > 0) {
            width = settings.width
            height = settings.height
        }
        DecoderStats.codecName = "${hw.mime.substringAfter('/')} · ${hw.name}"
        DecoderStats.lowLatency = hw.lowLatency
        Log.i(tag, "initDecode ${settings?.width}x${settings?.height} gen=$generation")
        return VideoCodecStatus.OK
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        if (frame == null) return VideoCodecStatus.ERR_PARAMETER
        try {
            return decodeInternal(frame)
        } catch (t: Throwable) {
            // Mai propagare eccezioni a libwebrtc (JNI abort): si chiede un keyframe e si riparte.
            Log.e(tag, "decode crashed: $t", t)
            keyFrameRequired = true
            return VideoCodecStatus.ERROR
        }
    }

    private fun decodeInternal(frame: EncodedImage): VideoCodecStatus {
        if (!decodeThreadTuned) {
            decodeThreadTuned = true
            setPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
        synchronized(codecLock) {
            var c = codec
            if (c == null) {
                // Surface non pronta o usata da un decoder più recente: scarta in silenzio
                // (niente richieste di keyframe a raffica al PC).
                val s = VideoSurface.acquire(this) ?: return VideoCodecStatus.OK
                if (!startCodecLocked(s)) {
                    VideoSurface.release(this)
                    return VideoCodecStatus.ERROR
                }
                c = codec ?: return VideoCodecStatus.ERROR
            }
            if (DecoderStats.keyFrameRequest) {
                DecoderStats.keyFrameRequest = false
                keyFrameRequired = true
                return NEED_KEY_FRAME
            }
            val isKey = frame.frameType == EncodedImage.FrameType.VideoFrameKey
            measureCadence(frame.captureTimeNs / 1_000_000)
            // --- protezione dal ritardo accumulato (decoder più lento del flusso) ---
            val lag = updateLag(frame.captureTimeNs / 1_000_000)
            if (catchingUp) {
                if (!isKey) {
                    // Scarta l'arretrato senza decodificarlo finché non arriva un keyframe "fresco".
                    DecoderStats.droppedForCatchUp.incrementAndGet()
                    return NEED_KEY_FRAME
                }
                catchingUp = false
                lagBaseline = Long.MIN_VALUE
                Log.i(tag, "catch-up done, resuming at keyframe")
            } else if (lag > maxLagMs && !keyFrameRequired) {
                catchingUp = true
                keyFrameRequired = true
                DecoderStats.catchUps.incrementAndGet()
                Log.w(tag, "decoder behind by $lag ms: dropping backlog and requesting a key frame")
                return NEED_KEY_FRAME
            }
            if (keyFrameRequired && !isKey) return NEED_KEY_FRAME
            val src = frame.buffer
            val size = src.remaining()
            if (size == 0) return VideoCodecStatus.OK
            return try {
                val index = if (primedInputIndex >= 0) primedInputIndex.also { primedInputIndex = -1 }
                else c.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) {
                    Log.w(tag, "decoder input full, frame dropped")
                    keyFrameRequired = true
                    return NEED_KEY_FRAME
                }
                val buf = c.getInputBuffer(index) ?: return NEED_KEY_FRAME
                if (size > buf.capacity()) {
                    Log.e(tag, "frame $size > input buffer ${buf.capacity()}")
                    c.queueInputBuffer(index, 0, 0, 0, 0)
                    keyFrameRequired = true
                    return NEED_KEY_FRAME
                }
                buf.clear()
                buf.put(src)
                val ptsUs = frame.captureTimeNs / 1000
                inputTimes[ptsUs] = SystemClock.elapsedRealtimeNanos()
                c.queueInputBuffer(index, 0, size, ptsUs, 0)
                keyFrameRequired = false
                DecoderStats.framesIn.incrementAndGet()
                DecoderStats.bytesIn.addAndGet(size.toLong())
                if (isKey) {
                    DecoderStats.keyFrames.incrementAndGet()
                    // Diagnostica: la dimensione dei keyframe decide quanto buffer RTP serve.
                    Log.i(tag, "key frame $size bytes")
                }
                VideoCodecStatus.OK
            } catch (e: Exception) {
                Log.e(tag, "decode failed: $e")
                stopCodecLocked()
                VideoSurface.release(this)
                keyFrameRequired = true
                VideoCodecStatus.ERROR
            }
        }
    }

    /**
     * Cadenza della sorgente e regolarità degli arrivi:
     *  - intervallo tipico = mediana degli ultimi intervalli RTP tra fotogrammi;
     *  - un intervallo RTP > 1,5x il tipico = fotogrammi che il PC non ha inviato (salti della sorgente);
     *  - |intervallo di arrivo - intervallo RTP| = jitter di arrivo (rete + coda di WebRTC), p95.
     */
    private fun measureCadence(rtpMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastRtpMs != Long.MIN_VALUE) {
            val dRtp = rtpMs - lastRtpMs
            val dArr = now - lastArrivalMs
            if (dRtp in 1..1000) {
                intervals.addLast(dRtp)
                if (intervals.size > 61) intervals.removeFirst()
                val sorted = intervals.sorted()
                val median = sorted[sorted.size / 2].coerceAtLeast(4)
                // I tempi RTP arrivano in ms interi (16/17 a 60 fps): media degli intervalli "normali".
                var sum = 0L
                var count = 0
                for (v in sorted) if (v * 2 >= median && v * 2 <= median * 3) { sum += v; count++ }
                val typical = if (count > 0) sum.toDouble() / count else median.toDouble()
                SmoothStats.frameIntervalMs = typical
                // Salto della sorgente = intervallo di oltre 1,85 fotogrammi (il jitter di
                // presentazione del PC arriva a 1,75: 29-30 ms). Oltre 250 ms è uno schermo fermo.
                if (intervals.size > 20 && dRtp > typical * 1.85 && dRtp <= 250) {
                    SmoothStats.sourceGaps.addAndGet(
                        (Math.round(dRtp / typical) - 1).coerceAtLeast(1L)
                    )
                }
                jitters.addLast(kotlin.math.abs((dArr - dRtp).toDouble()))
                if (jitters.size > 120) jitters.removeFirst()
                if (jitters.size >= 20) {
                    val s = jitters.sorted()
                    SmoothStats.arrivalJitterP95Ms = s[(s.size * 95 / 100).coerceAtMost(s.size - 1)]
                }
            }
        }
        lastRtpMs = rtpMs
        lastArrivalMs = now
    }

    /** Aggiorna la stima del ritardo accumulato e la restituisce (ms). */
    private fun updateLag(rtpMs: Long): Long {
        val now = SystemClock.elapsedRealtime()
        val offset = now - rtpMs
        if (lagBaseline == Long.MIN_VALUE || kotlin.math.abs(offset - lagBaseline) > 30_000) {
            // primo fotogramma, riavvolgimento del timestamp RTP o cambio di flusso
            lagBaseline = offset
            lagBaselineAt = now
        } else {
            // deriva lenta verso l'alto: 2 ms al secondo (tollera orologi che divergono)
            val elapsed = now - lagBaselineAt
            if (elapsed >= 1000) {
                lagBaseline += 2 * (elapsed / 1000)
                lagBaselineAt = now
            }
            if (offset < lagBaseline) lagBaseline = offset
        }
        val lag = offset - lagBaseline
        DecoderStats.lagMs = lag
        return lag
    }

    override fun release(): VideoCodecStatus {
        synchronized(codecLock) { stopCodecLocked() }
        VideoSurface.release(this)
        if (released.compareAndSet(false, true)) placeholder.release()
        callback = null
        Log.i(tag, "release gen=$generation")
        return VideoCodecStatus.OK
    }

    override fun onSurfaceLost() {
        synchronized(codecLock) {
            stopCodecLocked()
            keyFrameRequired = true
        }
    }

    /**
     * Configurazioni provate in ordine (la prima che parte davvero viene ricordata per quel decoder):
     * il decoder Realtek in low-latency usa un "tunnel" hardware che fallisce l'avvio se i buffer per la
     * riproduzione adattiva sono troppo grandi (4K), quindi il massimo resta alla risoluzione reale.
     */
    private data class Variant(val lowLatency: Boolean, val adaptiveMax: Boolean, val tuning: Boolean)

    private val variants = listOf(
        Variant(lowLatency = true, adaptiveMax = true, tuning = true),
        Variant(lowLatency = true, adaptiveMax = false, tuning = true),
        Variant(lowLatency = true, adaptiveMax = false, tuning = false),
        Variant(lowLatency = false, adaptiveMax = true, tuning = true),
        Variant(lowLatency = false, adaptiveMax = false, tuning = false),
    )

    /** Indice di input già prelevato durante la verifica di avvio, da usare al primo decode. */
    private var primedInputIndex = -1

    private fun startCodecLocked(surface: Surface): Boolean {
        if (codec != null) return true
        val first = workingVariant[hw.name] ?: 0
        for (i in first until variants.size) {
            val v = variants[i]
            if (v.lowLatency && !hw.lowLatency) continue
            val c = try {
                MediaCodec.createByCodecName(hw.name)
            } catch (e: Exception) {
                Log.e(tag, "createByCodecName(${hw.name}): $e")
                return false
            }
            if (!tryConfigure(c, surface, v)) {
                try { c.release() } catch (_: Exception) {}
                continue
            }
            try {
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.start()
                // Alcuni decoder segnalano l'errore subito dopo start(): lo si scopre qui, non al primo fotogramma.
                primedInputIndex = c.dequeueInputBuffer(30_000)
            } catch (e: Exception) {
                Log.w(tag, "start with $v failed: $e")
                try { c.release() } catch (_: Exception) {}
                continue
            }
            workingVariant[hw.name] = i
            codec = c
            running = true
            pacer = if (usePacer) VsyncPacer(VideoSurface.refreshHz, pacerSpare, pacerWindow, pacerBacklog) else null
            keyFrameRequired = true
            inputTimes.clear()
            DecoderStats.lowLatency = v.lowLatency
            outputThread = Thread({ outputLoop(c) }, "piik-dec-out").apply { start() }
            Log.i(tag, "codec ${hw.name} started ${width}x$height variant=$v gen=$generation")
            return true
        }
        Log.e(tag, "no working configuration for ${hw.name}")
        return false
    }

    private fun tryConfigure(c: MediaCodec, surface: Surface, v: Variant): Boolean {
        val fmt = MediaFormat.createVideoFormat(hw.mime, width, height)
        if (Build.VERSION.SDK_INT >= 30 && v.lowLatency) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (v.tuning && Build.VERSION.SDK_INT >= 23) {
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // tempo reale
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
        }
        if (v.adaptiveMax && hw.adaptive) {
            // Piik cambia livello 540p <-> 1080p sullo stesso flusso: basta il massimo reale (non 4K).
            fmt.setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(width, 1920))
            fmt.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(height, 1080))
        }
        return try {
            c.configure(fmt, surface, null, 0)
            true
        } catch (e: Exception) {
            Log.w(tag, "configure($v) failed: $e")
            false
        }
    }

    private fun stopCodecLocked() {
        val c = codec ?: return
        codec = null
        running = false
        primedInputIndex = -1
        pacer?.let {
            it.flush(c)
            it.stop()
        }
        pacer = null
        try { c.stop() } catch (_: Exception) {}
        try { outputThread?.join(300) } catch (_: InterruptedException) {}
        outputThread = null
        try { c.release() } catch (_: Exception) {}
        inputTimes.clear()
        Log.i(tag, "codec stopped gen=$generation")
    }

    private fun outputLoop(c: MediaCodec) {
        setPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val info = MediaCodec.BufferInfo()
        val next = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                c.dequeueOutputBuffer(info, 50_000)
            } catch (_: Exception) {
                break
            }
            val p = pacer
            if (idx >= 0 && p != null) {
                // Presentazione sincronizzata al refresh: il pacer sceglie il vsync giusto.
                val nowNs = System.nanoTime()
                val inNs = inputTimes[info.presentationTimeUs]
                    ?.let { nowNs - (SystemClock.elapsedRealtimeNanos() - it) } ?: 0L
                p.submit(c, idx, info.presentationTimeUs, nowNs, inNs)
                deliver(info.presentationTimeUs)
                continue
            }
            when {
                idx >= 0 -> {
                    var cur = idx
                    var curPts = info.presentationTimeUs
                    if (skipStaleFrames) {
                        // Latenza minima: se un fotogramma più recente è già pronto, mostra solo quello.
                        while (running) {
                            val n = try { c.dequeueOutputBuffer(next, 0) } catch (_: Exception) { -1 }
                            if (n >= 0) {
                                try { c.releaseOutputBuffer(cur, false) } catch (_: Exception) {}
                                deliver(curPts)
                                DecoderStats.framesSkipped.incrementAndGet()
                                cur = n
                                curPts = next.presentationTimeUs
                            } else {
                                if (n == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) onFormat(c)
                                break
                            }
                        }
                    }
                    try {
                        // Presentazione "adesso" con l'orologio della TV: con render=true Android userebbe
                        // il timestamp del fotogramma (derivato dall'orologio RTP del PC) come ora di
                        // presentazione desiderata, e il compositore potrebbe trattenere i buffer.
                        c.releaseOutputBuffer(cur, System.nanoTime())
                    } catch (_: Exception) {
                        break
                    }
                    deliver(curPts)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(c)
                else -> Unit
            }
        }
    }

    private fun onFormat(c: MediaCodec) {
        try {
            val f = c.outputFormat
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (f.containsKey("crop-right") && f.containsKey("crop-left") &&
                f.containsKey("crop-bottom") && f.containsKey("crop-top")
            ) {
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
            }
            DecoderStats.width = w
            DecoderStats.height = h
            Log.i(tag, "output format ${w}x$h $f")
            VideoSurface.onVideoSize?.invoke(w, h)
        } catch (e: Exception) {
            Log.w(tag, "format: $e")
        }
    }

    private fun deliver(ptsUs: Long) {
        val start = inputTimes.remove(ptsUs)
        var decodeMs: Int? = null
        if (start != null) {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            DecoderStats.onDecodeMs(ms)
            decodeMs = ms.toInt()
        }
        DecoderStats.framesOut.incrementAndGet()
        val cb = callback ?: return
        if (released.get()) return
        placeholder.retain()
        val vf = VideoFrame(placeholder, 0, ptsUs * 1000)
        try {
            cb.onDecodedFrame(vf, decodeMs, null)
        } catch (t: Throwable) {
            Log.w(tag, "onDecodedFrame: $t")
        } finally {
            vf.release()
        }
    }

    private fun setPriority(p: Int) {
        try { Process.setThreadPriority(p) } catch (_: Exception) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 200_000L

        /** Variante di configurazione che ha funzionato, per nome del decoder (vale per tutta la sessione). */
        private val workingVariant = ConcurrentHashMap<String, Int>()

        /**
         * "Serve un keyframe". NO_OUTPUT è un codice non negativo: libwebrtc non resetta il decoder
         * (cosa che fa per qualsiasi errore negativo, ~150 ms di blocco), ma lo considera un mancato
         * decode, quindi richiede subito un keyframe al PC e salta da sé i fotogrammi intermedi.
         */
        private val NEED_KEY_FRAME = VideoCodecStatus.NO_OUTPUT
    }
}
