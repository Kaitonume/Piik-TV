package it.mbs.piiktv.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Server Piik. In LAN piik-app espone il server stanze sulla porta 8787: in "Local room" (v2.5+)
 * in HTTPS con certificato auto-firmato, in "Public invite" in HTTP; in "Public invite" lo stesso
 * server è raggiungibile anche dal tunnel pubblico https://<nome>.trycloudflare.com (porta 443).
 */
data class PiikServer(
    val host: String,
    val port: Int = PiikHttp.DEFAULT_PORT,
    val secure: Boolean = false,
    val linkMode: Boolean = false,
    val sfu: Boolean = false,
    var name: String? = null,
) {
    // Origine esatta come la serializza il server (senza la porta predefinita dello schema):
    // "https://x.trycloudflare.com", non "https://x.trycloudflare.com:443", altrimenti 403.
    private val authority: String
        get() = if (port == (if (secure) 443 else 80)) host else "$host:$port"
    val origin: String get() = "${if (secure) "https" else "http"}://$authority"
    val signalUrl: String get() = "${if (secure) "wss" else "ws"}://$authority/signal"
    val label: String get() = name?.let { "$it  ($host)" } ?: host
    /** Indirizzo pubblico (nome di dominio) invece di un IP della rete di casa. */
    val isPublic: Boolean get() = !Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
}

/** Chiamate HTTP minime verso il server stanze di Piik. */
object PiikHttp {
    const val DEFAULT_PORT = 8787

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        // Una connessione tenuta nel pool e poi chiusa dal PC faceva fallire la richiesta successiva
        // (es. la ricerca del PC al rientro nell'app): con il nuovo tentativo OkHttp ne apre una nuova.
        .retryOnConnectionFailure(true)
        .sslSocketFactory(PiikTls.socketFactory, PiikTls.trustManager)
        .build()

    /** Client per il WebSocket di segnalazione: nessun timeout di lettura (il server invia ping ogni 30 s). */
    val wsClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * GET /api/capabilities: identifica un server Piik provando prima HTTPS (Local room) e poi HTTP
     * (Public invite / versioni precedenti). Ritorna null se non è Piik.
     */
    fun probe(host: String, port: Int = DEFAULT_PORT, fastClient: OkHttpClient = client): PiikServer? =
        probeScheme(host, port, true, fastClient) ?: probeScheme(host, port, false, fastClient)

    private fun probeScheme(host: String, port: Int, secure: Boolean, c: OkHttpClient): PiikServer? {
        val scheme = if (secure) "https" else "http"
        // "Connection: close": la sonda non lascia connessioni nel pool (la scansione ne apre decine).
        val req = Request.Builder().url("$scheme://$host:$port/api/capabilities")
            .header("Accept", "application/json").header("Connection", "close").build()
        return try {
            c.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                // Firma tipica di Piik: questi campi esistono solo nella sua risposta capabilities
                if (!j.has("hostRoomSession") || !j.has("roomInteractions")) return null
                PiikServer(host, port, secure = secure, linkMode = j.optBoolean("natPrediction", false), sfu = j.optBoolean("sfu", false))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * VibePiik (dalla 1.2.0): codice della stanza che il PC sta ospitando, letto solo dalla rete di
     * casa. null con Piik ufficiale, senza stanza o con l'ingresso col codice chiuso.
     */
    fun hostedRoom(server: PiikServer): String? {
        if (server.isPublic) return null
        val req = Request.Builder().url("${server.origin}/api/vibepiik/tv-room")
            .header("Accept", "application/json").header("Connection", "close").build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val room = JSONObject(resp.body?.string() ?: return null).optString("room")
                room.takeIf { Regex("^[1-9][0-9]{3}$").matches(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    data class SiteAccess(val required: Boolean, val authenticated: Boolean)

    fun siteAccess(server: PiikServer, cookie: String?): SiteAccess? {
        val b = Request.Builder().url("${server.origin}/api/site-access").header("Accept", "application/json")
        if (cookie != null) b.header("Cookie", cookie)
        return try {
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string() ?: return null)
                SiteAccess(j.optBoolean("required"), j.optBoolean("authenticated"))
            }
        } catch (_: Exception) {
            null
        }
    }

    sealed class LoginResult {
        data class Ok(val cookie: String?) : LoginResult()
        object WrongPassword : LoginResult()
        data class Failed(val reason: String) : LoginResult()
    }

    /** POST /api/site-access con la password del sito (localAccessPassword di piik-app). */
    fun login(server: PiikServer, password: String): LoginResult {
        val body = JSONObject().put("password", password).toString().toRequestBody(JSON)
        val req = Request.Builder().url("${server.origin}/api/site-access")
            .header("Origin", server.origin)
            .header("Accept", "application/json")
            .post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val cookie = resp.headers("Set-Cookie").firstOrNull { it.contains("piik-site-access") }
                            ?.substringBefore(';')?.trim()
                        LoginResult.Ok(cookie)
                    }
                    401 -> LoginResult.WrongPassword
                    else -> LoginResult.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            LoginResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
