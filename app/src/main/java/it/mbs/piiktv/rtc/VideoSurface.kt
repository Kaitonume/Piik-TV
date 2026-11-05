package it.mbs.piiktv.rtc

import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/**
 * Ponte tra la SurfaceView del player e il decoder hardware: il decoder rende i fotogrammi
 * direttamente su questa Surface (nessuna copia GPU), lo schermo li compone in overlay.
 *
 * Una Surface può essere collegata a un solo MediaCodec alla volta: durante un cambio di
 * connessione (nuovo percorso, ricostruzione) può esistere più di un decoder. Vince il più recente
 * (generazione più alta); i precedenti vengono fermati e non possono riprendersi lo schermo
 * finché il più recente non lo rilascia.
 */
object VideoSurface {
    interface Owner {
        val generation: Long
        /** La Surface non è più utilizzabile da questo decoder: deve fermare il MediaCodec subito. */
        fun onSurfaceLost()
    }

    private val lock = Object()
    private var surface: Surface? = null
    private var owner: Owner? = null
    private val generations = AtomicLong()

    /** Dimensioni video decodificate (dopo crop), per l'aspect ratio e lo scaler hardware. */
    @Volatile var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    /** Frequenza di refresh dello schermo (Hz), per il pacer sincronizzato al vsync. */
    @Volatile var refreshHz: Float = 60f

    fun nextGeneration(): Long = generations.incrementAndGet()

    /** Imposta la Surface dello schermo (null quando viene distrutta). */
    fun set(s: Surface?) {
        var lost: Owner? = null
        synchronized(lock) {
            if (s === surface) return
            lost = owner
            owner = null
            surface = s
        }
        lost?.onSurfaceLost()
    }

    fun available(): Boolean = synchronized(lock) { surface?.isValid == true }

    /**
     * Assegna la Surface al decoder [o]. Se la usa un decoder più vecchio, lo ferma prima di
     * restituirla. Ritorna null se la Surface non c'è o se la usa un decoder più recente.
     */
    fun acquire(o: Owner): Surface? {
        var previous: Owner? = null
        val s: Surface
        synchronized(lock) {
            val cur = surface?.takeIf { it.isValid } ?: return null
            val current = owner
            if (current === o) return cur
            if (current != null && current.generation > o.generation) return null
            previous = current
            owner = o
            s = cur
        }
        previous?.onSurfaceLost()
        return s
    }

    fun release(o: Owner) {
        synchronized(lock) { if (owner === o) owner = null }
    }
}
