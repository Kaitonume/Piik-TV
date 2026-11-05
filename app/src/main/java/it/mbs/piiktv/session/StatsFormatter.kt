package it.mbs.piiktv.session

import android.content.Context
import android.os.SystemClock
import it.mbs.piiktv.Prefs
import it.mbs.piiktv.R
import it.mbs.piiktv.rtc.DecoderStats
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.util.Locale

/**
 * Testo dell'overlay statistiche (tasto OK durante la visione). [ctx] fornisce i testi nella lingua
 * dell'interfaccia: i modelli stats_* vanno letti con getString(id) senza argomenti (testo grezzo,
 * "%%" compreso) e formattati qui con [numbers].
 */
class StatsFormatter(private val ctx: Context) {
    /** Formato dei numeri della lingua dei testi: it-IT (virgola decimale, come sempre) o en-US. */
    private val numbers: Locale = Locale.forLanguageTag(ctx.getString(R.string.stats_number_locale))
    private var lastAt = 0L
    private var lastBytes = 0.0
    private var lastPackets = 0.0
    private var lastLost = 0.0
    private var lastJbDelay = 0.0
    private var lastJbCount = 0.0
    private var lastOut = 0L
    private var lastFramesReceived = 0.0

    private var lastCpuTicks = -1L
    private var lastCpuAt = 0L
    private var lastGaps = 0L
    private var lastRepeats = 0L
    private var lastPacerDrops = 0L
    private var lastLogAt = 0L

    /** CPU usata dal processo dell'app, in % di un core (400% = tutti e 4 i core). */
    private fun cpuPercent(now: Long): Double {
        return try {
            val stat = java.io.File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // dopo ")" il campo 0 è lo stato: utime = campo 11, stime = campo 12 (tick da 10 ms)
            val ticks = fields[11].toLong() + fields[12].toLong()
            val pct = if (lastCpuTicks >= 0 && now > lastCpuAt) (ticks - lastCpuTicks) * 10.0 * 100 / (now - lastCpuAt) else -1.0
            lastCpuTicks = ticks
            lastCpuAt = now
            pct
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun format(report: RTCStatsReport?, latencyMode: Int, route: String): String {
        val now = SystemClock.elapsedRealtime()
        var video: RTCStats? = null
        var pair: RTCStats? = null
        val map = report?.statsMap ?: emptyMap()
        for (s in map.values) {
            when (s.type) {
                "inbound-rtp" -> if (s.members["kind"] == "video") video = s
                "candidate-pair" -> if (s.members["state"] == "succeeded" && s.members["nominated"] == true) {
                    if (pair == null || num(s.members["bytesReceived"]) > num(pair.members["bytesReceived"])) pair = s
                }
            }
        }
        val dt = if (lastAt == 0L) 0.0 else (now - lastAt) / 1000.0
        val sb = StringBuilder()
        val out = DecoderStats.framesOut.get()
        val outFps = if (dt > 0) (out - lastOut) / dt else 0.0
        val w = DecoderStats.width
        val h = DecoderStats.height
        sb.append(String.format(numbers, ctx.getString(R.string.stats_video), if (w > 0) "${w}×$h" else "—", outFps))
        sb.append("Decoder ").append(DecoderStats.codecName.ifEmpty { "—" })
            .append(if (DecoderStats.lowLatency) " · low-latency" else "").append('\n')
        var rtt = -1.0
        var localType = ""
        var remoteType = ""
        pair?.let { p ->
            rtt = num(p.members["currentRoundTripTime"]) * 1000
            val lc = map[p.members["localCandidateId"] as? String]
            val rc = map[p.members["remoteCandidateId"] as? String]
            localType = (lc?.members?.get("candidateType") as? String ?: "") + "/" + (lc?.members?.get("protocol") as? String ?: "")
            remoteType = rc?.members?.get("candidateType") as? String ?: ""
        }
        video?.let { v ->
            val m = v.members
            val bytes = num(m["bytesReceived"])
            val packets = num(m["packetsReceived"])
            val lost = num(m["packetsLost"])
            val jbDelay = num(m["jitterBufferDelay"])
            val jbCount = num(m["jitterBufferEmittedCount"])
            val framesReceived = num(m["framesReceived"])
            val mbps = if (dt > 0) (bytes - lastBytes) * 8 / dt / 1_000_000 else 0.0
            val dLost = lost - lastLost
            val dPk = packets - lastPackets
            val lossPct = if (dPk + dLost > 0) dLost * 100 / (dPk + dLost) else 0.0
            val jbMs = if (jbCount - lastJbCount > 0) (jbDelay - lastJbDelay) * 1000 / (jbCount - lastJbCount) else 0.0
            val inFps = if (dt > 0) (framesReceived - lastFramesReceived) / dt else 0.0
            sb.append(String.format(numbers, ctx.getString(R.string.stats_network),
                mbps, inFps, if (rtt >= 0) String.format(numbers, "%.0f ms", rtt) else "—", lossPct, num(m["jitter"]) * 1000))
            sb.append(String.format(numbers, ctx.getString(R.string.stats_buffer),
                jbMs, DecoderStats.decodeMsAvg, DecoderStats.framesSkipped.get() + num(m["framesDropped"]).toLong()))
            sb.append(String.format(numbers, ctx.getString(R.string.stats_repairs),
                num(m["nackCount"]).toLong(), num(m["pliCount"]).toLong(), DecoderStats.keyFrames.get(), num(m["freezeCount"]).toLong()))
            sb.append(String.format(numbers, ctx.getString(R.string.stats_lag),
                DecoderStats.lagMs, DecoderStats.catchUps.get(), DecoderStats.droppedForCatchUp.get()))
            val gaps = it.mbs.piiktv.rtc.SmoothStats.sourceGaps.get()
            val reps = it.mbs.piiktv.rtc.SmoothStats.repeats.get()
            val pdrops = it.mbs.piiktv.rtc.SmoothStats.pacerDrops.get()
            if (dt > 0) {
                sb.append(String.format(numbers,
                    ctx.getString(R.string.stats_smoothness),
                    (gaps - lastGaps) / dt, (reps - lastRepeats) / dt, (pdrops - lastPacerDrops) / dt,
                    it.mbs.piiktv.rtc.SmoothStats.arrivalJitterP95Ms,
                    if (it.mbs.piiktv.rtc.SmoothStats.pacerActive)
                        String.format(numbers, ctx.getString(R.string.stats_pacer_queued), it.mbs.piiktv.rtc.SmoothStats.pacerWaitMs)
                    else ctx.getString(R.string.stats_pacer_immediate)))
            }
            lastGaps = gaps; lastRepeats = reps; lastPacerDrops = pdrops
            if (now - lastLogAt >= 10_000) {
                // Contatori grezzi della ricezione nel log (diagnostica delle ritrasmissioni).
                lastLogAt = now
                android.util.Log.w("PiikStats", String.format(Locale.US,
                    "rtp packets=%d lost=%d nack=%d rtx=%d pli=%d framesRx=%d framesDropped=%d bytes=%d freezes=%d freezeMs=%d",
                    packets.toLong(), lost.toLong(), num(m["nackCount"]).toLong(),
                    num(m["retransmittedPacketsReceived"]).toLong(), num(m["pliCount"]).toLong(),
                    framesReceived.toLong(), num(m["framesDropped"]).toLong(), bytes.toLong(),
                    num(m["freezeCount"]).toLong(), (num(m["totalFreezesDuration"]) * 1000).toLong()))
            }
            if (DecoderStats.catchUps.get() >= 3 && outFps in 1.0..50.0) {
                sb.append(ctx.getString(R.string.stats_decode_warning))
            }
            lastBytes = bytes; lastPackets = packets; lastLost = lost; lastJbDelay = jbDelay; lastJbCount = jbCount
            lastFramesReceived = framesReceived
        } ?: sb.append(ctx.getString(R.string.stats_network_waiting))
        val modeName = when (latencyMode) {
            Prefs.LATENCY_MIN -> ctx.getString(R.string.stats_mode_min)
            Prefs.LATENCY_BALANCED -> ctx.getString(R.string.stats_mode_balanced)
            else -> ctx.getString(R.string.stats_mode_smooth)
        }
        sb.append(ctx.getString(R.string.stats_mode)).append(modeName).append("  ·  ").append(route)
        if (localType.isNotEmpty()) sb.append("  ·  ").append(localType).append(" ↔ ").append(remoteType)
        val cpu = cpuPercent(now)
        if (cpu >= 0) sb.append(String.format(numbers, ctx.getString(R.string.stats_cpu), cpu))
        lastAt = now
        lastOut = out
        return sb.toString()
    }
}
