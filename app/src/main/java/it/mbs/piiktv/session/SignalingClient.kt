package it.mbs.piiktv.session

import android.util.Log
import it.mbs.piiktv.net.PiikHttp
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * WebSocket di segnalazione Piik (protocollo piik-v24): ws://<pc>:8787/signal.
 * Il server accetta solo frame di testo JSON, richiede l'header Origin esatto e chiude la
 * connessione (1008) per qualsiasi campo inatteso: i messaggi in uscita vanno costruiti esatti.
 */
class SignalingClient(
    private val signalUrl: String,
    private val origin: String,
    private val cookie: String?,
    private val events: Events,
) {
    interface Events {
        fun onOpen(client: SignalingClient)
        fun onMessage(client: SignalingClient, msg: JSONObject)
        fun onClosed(client: SignalingClient, code: Int, reason: String, failure: Throwable?)
    }

    private val tag = "PiikSignal"
    @Volatile private var ws: WebSocket? = null
    @Volatile var closed = false
        private set

    fun connect() {
        val b = Request.Builder().url(signalUrl).header("Origin", origin)
        if (cookie != null) b.header("Cookie", cookie)
        ws = PiikHttp.wsClient.newWebSocket(b.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "open")
                events.onOpen(this@SignalingClient)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) {
                    Log.w(tag, "invalid json: $e")
                    return
                }
                events.onMessage(this@SignalingClient, j)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "closing $code $reason")
                webSocket.close(1000, null)
                finish(code, reason, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(code, reason, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "failure: $t (http ${response?.code})")
                val code = response?.code ?: 1006
                finish(if (code == 403) 4403 else code, t.message ?: "", t)
            }
        })
    }

    private fun finish(code: Int, reason: String, t: Throwable?) {
        if (closed) return
        closed = true
        events.onClosed(this, code, reason, t)
    }

    fun send(msg: JSONObject): Boolean {
        val w = ws ?: return false
        val text = msg.toString()
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "-> ${text.take(300)}")
        return w.send(text)
    }

    fun close() {
        closed = true
        ws?.close(1000, "viewer leaving")
        ws = null
    }
}
