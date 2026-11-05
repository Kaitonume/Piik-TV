package it.mbs.piiktv.rtc

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import it.mbs.piiktv.Prefs
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Creazione della PeerConnectionFactory con le impostazioni per la latenza minima:
 *  - field trial di libwebrtc per il ritardo di riproduzione (jitter buffer) secondo la modalità scelta;
 *  - buffer di ricezione UDP grande (4 MB) per non perdere pacchetti nei picchi dei keyframe;
 *  - audio a bassa latenza (AudioTrack PERFORMANCE_MODE_LOW_LATENCY), stereo, senza elaborazioni;
 *  - decoder video hardware diretto su Surface ([TvDecoderFactory]).
 */
object RtcEngine {
    private const val TAG = "PiikRtc"
    @Volatile private var initialized = false

    fun fieldTrials(mode: Int): String {
        val sb = StringBuilder()
        when (mode) {
            // Ritardo di riproduzione zero: il fotogramma va al decoder appena completo,
            // con un minimo di cadenza (8 ms) per non scaricare raffiche di fotogrammi tutti insieme.
            // Bilanciata: anche qui niente jitter buffer di WebRTC, perché il jitter lo assorbe
            // la coda del pacer sulla TV (VsyncPacer), che si adatta da sola con meno ritardo.
            Prefs.LATENCY_MIN, Prefs.LATENCY_BALANCED -> sb.append("WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/")
                .append("WebRTC-ZeroPlayoutDelay/min_pacing:8ms,max_decode_queue_size:6/")
            // Massima fluidità: jitter buffer adattivo standard di WebRTC.
            else -> Unit
        }
        sb.append("WebRTC-ReceiveBufferSize/size_bytes:4194304/")
        // Dimensione massima del buffer RTP. Se un pacchetto manca, i fotogrammi successivi restano in
        // attesa finché il buffer non si riempie; allora WebRTC lo svuota e chiede un keyframe.
        //  - 2048 (standard, ~1,6 s a 12 Mbit/s): con un pacchetto perso davvero la TV restava ferma
        //    ~1,7 s prima di chiedere il keyframe.
        //  - 512 (~0,4 s): blocchi brevi, ma con disturbi Wi-Fi una ritrasmissione in ritardo di
        //    qualche decimo di secondo fa traboccare il buffer; il keyframe chiesto (fino a ~115 KB,
        //    ~90 pacchetti) lo riempie in parte e il successivo ritardo lo fa traboccare di nuovo: si va
        //    avanti a raffiche (misurati 119 blocchi in 7 minuti, uno per keyframe, con 0 pacchetti
        //    persi: arrivavano solo tardi).
        //  - 1024 (~0,8 s): il doppio del margine per le ritrasmissioni e un caso peggiore sotto la
        //    metà di quello standard. A rete tranquilla nessuna differenza misurata (A/B di 5 minuti
        //    con 512, 1024 e 2048; con contenuto in movimento 1 blocco in 200 s, nessun keyframe chiesto).
        sb.append("WebRTC-PacketBufferMaxSize/${packetBufferMaxSize()}/")
        return sb.toString()
    }

    private const val PACKET_BUFFER_MAX_SIZE = 1024

    /** Diagnostica (adb shell setprop debug.piiktv.pbmax <pacchetti>): prova altre dimensioni del buffer RTP. */
    private fun packetBufferMaxSize(): Int = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, "debug.piiktv.pbmax") as String).toIntOrNull()
            ?.takeIf { it in 64..8192 } ?: PACKET_BUFFER_MAX_SIZE
    } catch (_: Throwable) {
        PACKET_BUFFER_MAX_SIZE
    }

    @Synchronized
    fun initialize(ctx: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .setFieldTrials(fieldTrials(Prefs.latencyMode))
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        initialized = true
    }

    /** Crea una factory nuova per ogni sessione: così il cambio di modalità latenza vale subito. */
    fun createFactory(ctx: Context, mode: Int): PeerConnectionFactory {
        initialize(ctx)
        val trials = fieldTrials(mode)
        Log.i(TAG, "factory field trials: $trials")
        val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setUseLowLatency(true)
            .setUseStereoOutput(true)
            .setUseStereoInput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setFieldTrials(trials)
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(TvDecoderFactory(mode))
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .createPeerConnectionFactory()
        // La factory tiene il suo riferimento nativo all'ADM.
        adm.release()
        return factory
    }
}
