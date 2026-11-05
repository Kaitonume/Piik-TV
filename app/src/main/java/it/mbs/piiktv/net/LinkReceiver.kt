package it.mbs.piiktv.net

import android.content.Context
import android.util.Log
import it.mbs.piiktv.AppLocale
import it.mbs.piiktv.R
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

/**
 * Mini server HTTP sulla TV per ricevere un link di invito di Piik dal PC (o dal telefono) invece di
 * scriverlo con il telecomando: si apre http://<ip-della-tv>:<porta> nel browser, si incolla il
 * link e lo si invia; oppure l'app Piik sul PC lo manda da sola (pulsante «Manda URL all'app PIIK
 * TV»), trovando la TV con GET /info. È attivo mentre l'app è aperta sulla TV e accetta soltanto
 * link di stanze Piik (…/r/<codice>, con l'eventuale invito #v=…).
 *
 * La pagina è in italiano se il browser preferisce l'italiano (Accept-Language), altrimenti in
 * inglese; [ctx] (Context dell'applicazione) serve per leggerne i testi.
 */
class LinkReceiver(private val ctx: Context, private val name: () -> String, private val onLink: (String) -> Unit) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val italianTexts: Context by lazy { AppLocale.forLanguage(ctx, AppLocale.ITALIAN) }
    private val englishTexts: Context by lazy { AppLocale.forLanguage(ctx, AppLocale.ENGLISH) }

    val port: Int get() = server?.localPort ?: -1

    fun start(): Boolean = try {
        val s = try { ServerSocket(PREFERRED_PORT) } catch (_: Exception) { ServerSocket(0) }
        server = s
        running = true
        Thread({ loop(s) }, "piik-link").start()
        true
    } catch (e: Exception) {
        Log.w(TAG, "start: $e")
        false
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun loop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            Thread({ handle(c) }, "piik-link-req").start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 5000
                val input = sock.getInputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: "/"
                // Lingua delle pagine HTML (valutata solo per quelle: /info non cambia).
                val italian by lazy {
                    prefersItalian(lines.firstOrNull { it.startsWith("accept-language:", ignoreCase = true) }?.substringAfter(':'))
                }
                if (method == "GET" && path.startsWith("/info")) {
                    // Identificazione per l'app Piik sul PC che cerca le TV in rete.
                    val info = org.json.JSONObject().put("app", "piik-tv").put("name", name()).toString()
                    respond(sock, 200, info, "application/json")
                } else if (method == "POST" && path.startsWith("/link")) {
                    val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(length.coerceIn(0, MAX_BODY))
                    readFully(input, body)
                    val link = String(body, Charsets.UTF_8).split('&')
                        .firstOrNull { it.startsWith("link=") }?.substringAfter("link=")
                        ?.let { URLDecoder.decode(it, "UTF-8") }?.trim()
                    if (link != null && LINK.matches(link)) {
                        respond(sock, 200, page(italian, R.string.link_sent_title, R.string.link_sent_text, false))
                        onLink(link)
                    } else {
                        respond(sock, 400, page(italian, R.string.link_invalid_title, R.string.link_invalid_text, true))
                    }
                } else {
                    respond(sock, 200, page(italian, R.string.link_form_title, R.string.link_form_text, true))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "request: $e")
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun respond(sock: Socket, code: Int, content: String, type: String = "text/html") {
        val body = content.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "400 Bad Request"
        // CORS aperto: anche una pagina web (es. Piik nel browser) può inviare il link direttamente.
        val header = "HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun page(italian: Boolean, titleId: Int, textId: Int, form: Boolean): String {
        val texts = if (italian) italianTexts else englishTexts
        val lang = if (italian) "it" else "en"
        val title = texts.getString(titleId)
        val text = texts.getString(textId)
        val send = texts.getString(R.string.link_send_button)
        return """
        <!doctype html><html lang="$lang"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Piik TV</title>
        <style>body{font-family:system-ui,sans-serif;background:#100c1e;color:#eee;display:flex;justify-content:center;padding:8vh 16px}
        main{max-width:640px;width:100%}h1{font-size:1.6em}p{color:#bbb}
        input{width:100%;box-sizing:border-box;padding:12px;font-size:1em;border-radius:8px;border:1px solid #555;background:#1c1830;color:#fff}
        button{margin-top:12px;padding:12px 20px;font-size:1em;border:0;border-radius:8px;background:#7c5cff;color:#fff;cursor:pointer}</style>
        </head><body><main><h1>$title</h1><p>$text</p>
        ${if (form) """<form method="post" action="/link"><input name="link" type="text" autofocus required
            placeholder="https://….trycloudflare.com/r/1234#v=…"><button type="submit">$send</button></form>""" else ""}
        </main></body></html>
    """.trimIndent()
    }

    companion object {
        private const val TAG = "PiikLink"
        const val PREFERRED_PORT = 8790
        private const val MAX_BODY = 8192
        /** Link di stanza Piik: http(s)://host[:porta]/r/<codice>[...][#v=<invito>] */
        val LINK = Regex("^https?://[A-Za-z0-9.\\-]+(?::\\d{1,5})?/r/[1-9][0-9]{3}[^\\s]*$")

        /**
         * true se l'intestazione Accept-Language (es. "it-IT,it;q=0.9,en;q=0.8") mette l'italiano
         * prima dell'inglese: peso q più alto, a parità di peso quello elencato prima. Senza
         * intestazione, o senza italiano accettabile, la pagina è in inglese.
         */
        fun prefersItalian(header: String?): Boolean {
            if (header.isNullOrBlank()) return false
            var itQ = 0.0
            var itPos = Int.MAX_VALUE
            var enQ = 0.0
            var enPos = Int.MAX_VALUE
            header.split(',').forEachIndexed { pos, entry ->
                val fields = entry.split(';')
                val lang = fields[0].trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
                val q = fields.drop(1).map { it.split('=', limit = 2) }
                    .firstOrNull { it.size == 2 && it[0].trim().equals("q", ignoreCase = true) }
                    ?.get(1)?.trim()?.toDoubleOrNull() ?: 1.0
                if (q <= 0.0) return@forEachIndexed
                if (lang == "it" && q > itQ) { itQ = q; itPos = pos }
                if (lang == "en" && q > enQ) { enQ = q; enPos = pos }
            }
            return itQ > 0.0 && (itQ > enQ || (itQ == enQ && itPos < enPos))
        }
    }
}
