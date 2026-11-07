package it.mbs.piiktv.rtc

import android.util.Log
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

/**
 * Fabbrica dei decoder video. Per ogni codec con decoder hardware usa [DirectVideoDecoder]
 * (MediaCodec low-latency -> Surface dello schermo); per gli altri ripiega sui decoder software
 * di libwebrtc (libvpx / dav1d).
 *
 * L'elenco dei codec è costruito da MediaCodecList e non dal filtro di libwebrtc, che scarta i
 * decoder hardware che non dichiarano formati colore YUV (come quelli Realtek che lavorano solo su Surface).
 */
class TvDecoderFactory(private val latencyMode: Int) : VideoDecoderFactory {
    private val skipStaleFrames = latencyMode == it.mbs.piiktv.Prefs.LATENCY_MIN
    private val maxLagMs = when (latencyMode) {
        it.mbs.piiktv.Prefs.LATENCY_MIN -> 150L
        it.mbs.piiktv.Prefs.LATENCY_BALANCED -> 250L
        else -> 500L
    }
    private val software = SoftwareVideoDecoderFactory()

    private fun h264(profileLevelId: String) = VideoCodecInfo(
        "H264",
        mapOf(
            VideoCodecInfo.H264_FMTP_LEVEL_ASYMMETRY_ALLOWED to "1",
            VideoCodecInfo.H264_FMTP_PACKETIZATION_MODE to "1",
            VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID to profileLevelId,
        ),
        emptyList(),
    )

    private val supported: Array<VideoCodecInfo> by lazy {
        val list = ArrayList<VideoCodecInfo>()
        val sw = software.supportedCodecs.toList()
        if (HwCodecs.find(MIME_H264) != null) {
            // Constrained High, High, Main, Constrained Baseline, Baseline
            list += h264("640c1f")
            list += h264("64001f")
            list += h264("4d001f")
            list += h264("42e01f")
            list += h264("42001f")
        }
        // HEVC (VibePiik): solo hardware, il profilo Main predefinito basta.
        if (HwCodecs.find(MIME_H265) != null) list += VideoCodecInfo("H265", emptyMap(), emptyList())
        for (name in listOf("VP8", "VP9", "AV1")) {
            val mime = mimeFor(name) ?: continue
            if (HwCodecs.find(mime) != null) {
                // Per VP9 il profilo 0 basta (8 bit). Usa i parametri del software se presenti.
                val swInfo = sw.firstOrNull { it.name.equals(name, ignoreCase = true) }
                list += swInfo ?: VideoCodecInfo(name, emptyMap(), emptyList())
            } else {
                sw.filter { it.name.equals(name, ignoreCase = true) }.forEach { list += it }
            }
        }
        Log.i("PiikDecoder", "supported codecs: ${list.joinToString { it.name + it.params }}")
        list.toTypedArray()
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = supported

    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder? {
        if (info == null) return null
        val mime = mimeFor(info.name)
        var hw = mime?.let { HwCodecs.find(it) }
        if (mime == "video/x-vnd.on2.vp8" && it.mbs.piiktv.Prefs.vp8Decoder == 2) {
            HwCodecs.findSoftware(mime)?.let { hw = it }
        }
        val chosen = hw
        if (chosen != null) {
            Log.i("PiikDecoder", "createDecoder ${info.name} -> direct ${chosen.name}")
            val smooth = latencyMode == it.mbs.piiktv.Prefs.LATENCY_SMOOTH
            return DirectVideoDecoder(
                chosen, skipStaleFrames, maxLagMs, usePacer = !skipStaleFrames,
                // Parametri scelti simulando la presentazione sugli arrivi reali registrati sulla TV.
                pacerSpare = if (smooth) 1 else 0,
                pacerWindow = if (smooth) 480 else 240,
                pacerBacklog = if (smooth) 10 else 6,
            )
        }
        Log.i("PiikDecoder", "createDecoder ${info.name} -> software")
        return software.createDecoder(info)
    }

    companion object {
        const val MIME_H264 = "video/avc"
        const val MIME_H265 = "video/hevc"
        fun mimeFor(name: String): String? = when (name.uppercase()) {
            "H264" -> MIME_H264
            "H265" -> MIME_H265
            "VP8" -> "video/x-vnd.on2.vp8"
            "VP9" -> "video/x-vnd.on2.vp9"
            "AV1" -> "video/av01"
            else -> null
        }
    }
}
