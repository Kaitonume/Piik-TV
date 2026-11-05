package it.mbs.piiktv.rtc

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Contatori della fluidità (overlay statistiche). */
object SmoothStats {
    /** Fotogrammi che il PC non ha inviato (salti nel timestamp RTP della sorgente). */
    val sourceGaps = AtomicLong()
    /** Refresh dello schermo senza un fotogramma nuovo mentre il flusso è attivo. */
    val repeats = AtomicLong()
    /** Fotogrammi scartati dal pacer per ridurre la latenza (o per un arretrato). */
    val pacerDrops = AtomicLong()
    /** Fotogrammi presentati dal pacer. */
    val paced = AtomicLong()
    @Volatile var arrivalJitterP95Ms = 0.0
    @Volatile var frameIntervalMs = 16.7
    @Volatile var pacerActive = false
    /** Attesa media tra fotogramma decodificato e presentazione a schermo (ms). */
    @Volatile var pacerWaitMs = 0.0

    fun reset() {
        sourceGaps.set(0); repeats.set(0); pacerDrops.set(0); paced.set(0)
        arrivalJitterP95Ms = 0.0; frameIntervalMs = 16.7; pacerActive = false; pacerWaitMs = 0.0
    }
}

/**
 * Presentazione "in coda" sincronizzata con il refresh della TV: a ogni vsync va a schermo il
 * fotogramma successivo, in ordine, uno per refresh.
 *
 * Perché non i timestamp di cattura: sul PC un video a 60 fps su un desktop a 240 Hz viene composto
 * con 4-12 ms di jitter (intervalli di 8, 12,5, 21 e 25 ms attorno a 16,7). Rispettare quei tempi,
 * o mostrare sempre l'ultimo fotogramma pronto, fa cadere due fotogrammi nello stesso refresh e
 * nessuno in quello dopo: uno scarto più una ripetizione, ~7 volte al secondo (simulazione sui
 * timestamp reali del PC). In coda ogni fotogramma ha il suo refresh.
 *
 * Il resto lo decide la rete: su Wi-Fi a 2,4 GHz il ritardo dei fotogrammi varia di ~18 ms tipici,
 * ~35 ms al 95° e ~55 ms al 99° percentile (misurato); la coda si porta da sola a ~3 fotogrammi
 * (~65 ms) e restano ~0,3-0,7 difetti al secondo. Su una rete stabile (Ethernet) resta corta.
 *
 * Latenza adattiva: quando un fotogramma arriva tardi la coda si allunga da sola di un refresh
 * (ripetizione inevitabile); se per [window] refresh di fila resta sempre almeno un fotogramma in
 * più della scorta [spare], se ne scarta uno (il più vicino al precedente, il meno visibile) per
 * tornare verso la diretta. Le pause vere della sorgente (intervallo >= 1,85 fotogrammi) vengono
 * riprodotte tenendo il fotogramma un refresh in più, invece di "saltare" ora e ripetere dopo.
 *
 * In "latenza minima" il pacer non è usato: ogni fotogramma va a schermo appena decodificato.
 */
class VsyncPacer(
    refreshHz: Float,
    /** Fotogrammi di scorta da mantenere in coda (0 = bilanciata, 1 = massima fluidità). */
    private val spare: Int,
    /** Refresh consecutivi con scorta in eccesso prima di scartare un fotogramma. */
    private val window: Int,
    /**
     * Oltre questa lunghezza la coda è un arretrato (pausa lunga del Wi-Fi seguita da una raffica)
     * e si torna subito vicino alla diretta. Deve superare le raffiche normali: con un limite di 3
     * il Wi-Fi a 2,4 GHz (ritardi extra p99 ~55 ms) causava ~2 scarti al secondo.
     */
    private val backlog: Int = 6,
) : Choreographer.FrameCallback {
    private class Pending(val codec: MediaCodec, val index: Int, val ptsMs: Double, val readyNs: Long)

    private val tag = "PiikPacer"
    private val lock = Object()
    private val queue = ArrayDeque<Pending>()
    private val thread = HandlerThread("piik-vsync", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null
    @Volatile private var running = true
    private val periodNs = (1_000_000_000.0 / refreshHz.coerceIn(23f, 240f)).toLong()
    private val periodMs = periodNs / 1_000_000.0
    @Volatile private var lastReadyNs = 0L

    // Stato del thread del vsync.
    private var lastShownPts = Double.NaN
    private var sinceShow = 0
    private var minDepth = Int.MAX_VALUE
    private var windowCount = 0
    private var waitAvgMs = 0.0
    /** Refresh senza fotogramma nuovo dall'ultimo mostrato (attribuiti al PC o alla TV al prossimo). */
    private var starvedRun = 0
    private var logCount = 0

    init {
        handler.post { choreographer = Choreographer.getInstance().also { it.postFrameCallback(this) } }
        SmoothStats.pacerActive = true
    }

    // Diagnostica (adb shell setprop debug.piiktv.trace 1): tempi RTP e di decodifica di ogni
    // fotogramma nel log, per simulare offline la presentazione con gli arrivi reali.
    private val trace: StringBuilder? = if (traceEnabled()) StringBuilder(2048) else null
    private var traceCount = 0

    /**
     * Fotogramma decodificato: [ptsUs] è il timestamp di cattura (RTP) passato al decoder,
     * [inputNs] l'istante (System.nanoTime) in cui è entrato nel decoder, 0 se ignoto.
     */
    fun submit(codec: MediaCodec, index: Int, ptsUs: Long, readyNs: Long, inputNs: Long = 0) {
        trace?.let {
            it.append(ptsUs / 100).append(',').append(inputNs / 100_000).append(',')
                .append(readyNs / 100_000).append(';')
            if (++traceCount >= 50) {
                Log.w(TRACE_TAG, it.toString())
                it.setLength(0)
                traceCount = 0
            }
        }
        val p = Pending(codec, index, ptsUs / 1000.0, readyNs)
        var overflow: Pending? = null
        synchronized(lock) {
            queue.addLast(p)
            // Se il thread del vsync si bloccasse, non trattenere tutti i buffer del decoder.
            if (queue.size > backlog + 2) overflow = queue.removeFirst()
        }
        overflow?.let { release(it); SmoothStats.pacerDrops.incrementAndGet() }
        lastReadyNs = readyNs
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Il fotogramma rilasciato ora va a schermo al refresh successivo.
        val presentNs = frameTimeNanos + periodNs
        val active = lastReadyNs != 0L && System.nanoTime() - lastReadyNs < 250_000_000L
        val nominal = SmoothStats.frameIntervalMs.coerceAtLeast(periodMs)
        // Sorgenti più lente del refresh (es. 30 fps su 60 Hz): ogni fotogramma resta N refresh.
        val minHold = (nominal / periodMs).roundToInt().coerceIn(1, 4)
        var show: Pending? = null
        var starved = false
        val dropped = ArrayList<Pending>(2)
        synchronized(lock) {
            // Arretrato (es. dopo un blocco del Wi-Fi): torna subito vicino alla diretta.
            if (queue.size > backlog) {
                while (queue.size > spare + 1) dropped += queue.removeFirst()
            }
            val head = queue.firstOrNull()
            if (head == null) {
                starved = sinceShow + 1 >= minHold
            } else {
                var hold = false
                if (queue.size <= spare + 1) {
                    if (sinceShow + 1 < minHold) {
                        hold = true
                    } else if (!lastShownPts.isNaN()) {
                        val gap = head.ptsMs - lastShownPts
                        if (gap >= 1.85 * nominal && gap <= 4 * nominal &&
                            sinceShow + 1 < (gap / periodMs).roundToInt()
                        ) {
                            // Un fotogramma in ritardo seguito subito dal successivo non è una pausa vera.
                            val second = queue.elementAtOrNull(1)
                            hold = second == null || second.ptsMs - head.ptsMs >= 0.5 * nominal
                        }
                    }
                }
                if (!hold) show = queue.removeFirst()
            }
            if (active || show != null || queue.isNotEmpty()) {
                if (queue.size < minDepth) minDepth = queue.size
                if (++windowCount >= window) {
                    if (minDepth >= spare + 1 && queue.isNotEmpty()) dropped += removeClosestLocked(show)
                    windowCount = 0
                    minDepth = Int.MAX_VALUE
                }
            }
        }
        for (p in dropped) { release(p); SmoothStats.pacerDrops.incrementAndGet() }
        val s = show
        if (s != null) {
            try {
                s.codec.releaseOutputBuffer(s.index, presentNs)
                SmoothStats.paced.incrementAndGet()
            } catch (e: Exception) {
                Log.w(tag, "release: $e")
            }
            if (starvedRun > 0) {
                // Refresh rimasti senza fotogramma: se quello arrivato ora segue una pausa vera del
                // PC (fotogrammi mai inviati) quei refresh sono della sorgente, non difetti della TV.
                var tvRepeats = starvedRun
                if (!lastShownPts.isNaN()) {
                    val gap = s.ptsMs - lastShownPts
                    if (gap >= 1.85 * nominal) tvRepeats -= ((gap / periodMs).roundToInt() - 1).coerceAtLeast(0)
                }
                if (tvRepeats > 0) SmoothStats.repeats.addAndGet(tvRepeats.toLong())
                starvedRun = 0
            }
            lastShownPts = s.ptsMs
            sinceShow = 0
            waitAvgMs = waitAvgMs * 0.95 + (presentNs - s.readyNs) / 1_000_000.0 * 0.05
            SmoothStats.pacerWaitMs = waitAvgMs
        } else {
            sinceShow++
            if (starved && active && !lastShownPts.isNaN()) starvedRun++
        }
        if (++logCount >= LOG_EVERY_FRAMES) {
            logCount = 0
            Log.w(STATS_TAG, String.format(java.util.Locale.US,
                "shown=%d repeats=%d drops=%d gaps=%d wait=%.1f jitterP95=%.1f key=%d out=%d lag=%d",
                SmoothStats.paced.get(), SmoothStats.repeats.get(), SmoothStats.pacerDrops.get(),
                SmoothStats.sourceGaps.get(), SmoothStats.pacerWaitMs, SmoothStats.arrivalJitterP95Ms,
                DecoderStats.keyFrames.get(), DecoderStats.framesOut.get(), DecoderStats.lagMs))
        }
        choreographer?.postFrameCallback(this)
    }

    /** Toglie dalla coda il fotogramma più vicino (nel tempo della sorgente) al precedente. */
    private fun removeClosestLocked(shown: Pending?): Pending {
        var prev = shown?.ptsMs ?: lastShownPts
        var best = 0
        var bestDelta = Double.MAX_VALUE
        queue.forEachIndexed { i, p ->
            val d = if (prev.isNaN()) 0.0 else p.ptsMs - prev
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
            prev = p.ptsMs
        }
        return queue.removeAt(best)
    }

    private fun release(p: Pending) {
        try { p.codec.releaseOutputBuffer(p.index, false) } catch (_: Exception) {}
    }

    /** Rilascia senza mostrarli i buffer di [codec] ancora in coda (prima di fermarlo). */
    fun flush(codec: MediaCodec) {
        val toRelease = ArrayList<Pending>()
        synchronized(lock) {
            val it = queue.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.codec === codec) { toRelease += p; it.remove() }
            }
        }
        for (p in toRelease) release(p)
    }

    fun stop() {
        running = false
        synchronized(lock) { queue.clear() }
        handler.post {
            choreographer?.removeFrameCallback(this)
            thread.quitSafely()
        }
        SmoothStats.pacerActive = false
    }

    companion object {
        private const val STATS_TAG = "PiikStats"
        private const val TRACE_TAG = "PiikTrace"
        /** Riga di statistiche nel log ogni ~10 s (a 60 Hz). */
        private const val LOG_EVERY_FRAMES = 600

        private fun traceEnabled(): Boolean = try {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java).invoke(null, "debug.piiktv.trace") == "1"
        } catch (_: Throwable) {
            false
        }
    }
}
