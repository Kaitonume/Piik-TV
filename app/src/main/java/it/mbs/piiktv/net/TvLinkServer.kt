package it.mbs.piiktv.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import it.mbs.piiktv.MainActivity
import it.mbs.piiktv.Prefs

/**
 * Ricevitore dei link di invito attivo per tutto il tempo in cui l'app è in primo piano sulla TV
 * (menu o visione): le attività chiamano [onStart]/[onStop] e il server si spegne quando l'app va
 * in secondo piano. Un link ricevuto apre la stanza come un link aperto con «Indirizzo o link…»,
 * anche durante la visione (lo stream attuale viene chiuso e si passa alla nuova stanza).
 */
object TvLinkServer {
    private const val TAG = "PiikLink"
    // Il LinkReceiver tiene solo il Context dell'applicazione (vedi onStart), non quello di un'attività.
    @SuppressLint("StaticFieldLeak")
    private var receiver: LinkReceiver? = null
    private var visible = 0

    /** Porta in ascolto, -1 se spento. */
    val port: Int get() = receiver?.port ?: -1

    @Synchronized
    fun onStart(ctx: Context) {
        visible++
        if (receiver != null) return
        val app = ctx.applicationContext
        val r = LinkReceiver(app, { Prefs.displayName }) { link -> deliver(app, link) }
        if (r.start()) {
            receiver = r
            Log.i(TAG, "receiver on port ${r.port}")
        }
    }

    @Synchronized
    fun onStop() {
        visible = (visible - 1).coerceAtLeast(0)
        if (visible == 0) {
            receiver?.stop()
            receiver = null
        }
    }

    private fun deliver(app: Context, link: String) {
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "deliver: $e")
        }
    }
}
