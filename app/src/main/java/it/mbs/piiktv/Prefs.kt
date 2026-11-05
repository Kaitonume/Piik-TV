package it.mbs.piiktv

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.text.Normalizer

/** Preferenze persistenti dell'app (impostazioni, server noti, codici stanza ricordati). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** Lingua dell'interfaccia: [AppLocale.AUTO] (lingua di sistema), [AppLocale.ENGLISH] o [AppLocale.ITALIAN]. */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, null) ?: AppLocale.AUTO
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    /** Come [language], ma senza [init]: serve in attachBaseContext, che arriva prima di onCreate. */
    fun languageOf(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null) ?: AppLocale.AUTO

    /** 0 = latenza minima, 1 = bilanciata, 2 = massima fluidità. */
    var latencyMode: Int
        get() = sp.getInt("latencyMode", LATENCY_BALANCED)
        set(v) = sp.edit().putInt("latencyMode", v).apply()

    var audioEnabled: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    var showStats: Boolean
        get() = sp.getBoolean("stats", false)
        set(v) = sp.edit().putBoolean("stats", v).apply()

    /** Sincronizzazione labiale audio/video (aggiunge latenza al video). */
    var avSync: Boolean
        get() = sp.getBoolean("avSync", false)
        set(v) = sp.edit().putBoolean("avSync", v).apply()

    /** Decoder VP8: 0 = automatico, 1 = hardware, 2 = software multi-thread (libvpx). */
    var vp8Decoder: Int
        get() = sp.getInt("vp8Decoder", 0)
        set(v) = sp.edit().putInt("vp8Decoder", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", true)
        set(v) = sp.edit().putBoolean("autoConnect", v).apply()

    var displayName: String
        get() = sp.getString("displayName", null) ?: "TV"
        set(v) = sp.edit().putString("displayName", sanitizeDisplayName(v)).apply()

    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(v) = sp.edit().putString("lastHost", v).apply()

    var knownHosts: Set<String>
        get() = sp.getStringSet("knownHosts", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("knownHosts", HashSet(v)).apply()

    fun rememberHost(host: String) {
        lastHost = host
        knownHosts = knownHosts + host
    }

    fun roomFor(host: String): String? = sp.getString("room:$host", null)
    fun setRoomFor(host: String, room: String) = sp.edit().putString("room:$host", room).apply()

    fun grantFor(host: String, room: String): String? = sp.getString("grant:$host:$room", null)
    fun setGrantFor(host: String, room: String, grant: String?) =
        sp.edit().apply { if (grant == null) remove("grant:$host:$room") else putString("grant:$host:$room", grant) }.apply()

    fun viewerPasswordFor(host: String, room: String): String? = sp.getString("vpw:$host:$room", null)
    fun setViewerPasswordFor(host: String, room: String, pw: String?) =
        sp.edit().apply { if (pw.isNullOrEmpty()) remove("vpw:$host:$room") else putString("vpw:$host:$room", pw) }.apply()

    fun sitePasswordFor(host: String): String? = sp.getString("spw:$host", null)
    fun setSitePasswordFor(host: String, pw: String?) =
        sp.edit().apply { if (pw.isNullOrEmpty()) remove("spw:$host") else putString("spw:$host", pw) }.apply()

    fun hostName(host: String): String? = sp.getString("name:$host", null)
    fun setHostName(host: String, name: String) = sp.edit().putString("name:$host", name).apply()

    /** Porta e HTTPS dell'host (serve per gli indirizzi pubblici, che la ricerca in LAN non trova). */
    fun portFor(host: String): Int = sp.getInt("port:$host", if (host.any { it.isLetter() }) 443 else 8787)
    fun secureFor(host: String): Boolean = sp.getBoolean("secure:$host", host.any { it.isLetter() })
    fun setEndpointFor(host: String, port: Int, secure: Boolean) =
        sp.edit().putInt("port:$host", port).putBoolean("secure:$host", secure).apply()

    /** clientId stabile per stanza: Piik riusa lo stesso posto se ci si riconnette. */
    fun clientIdFor(host: String, room: String): String {
        val key = "cid:$host:$room"
        sp.getString(key, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString(key, id).apply()
        return id
    }

    /** Piik accetta nomi già normalizzati NFC, 1-24 caratteri, senza caratteri di controllo/bidi. */
    fun sanitizeDisplayName(raw: String): String {
        val nfc = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val sb = StringBuilder()
        var count = 0
        var i = 0
        while (i < nfc.length && count < 24) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val isControl = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.SURROGATE.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (isControl) continue
            sb.appendCodePoint(cp)
            count++
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) "TV" else out
    }

    const val LATENCY_MIN = 0
    const val LATENCY_BALANCED = 1
    const val LATENCY_SMOOTH = 2

    private const val FILE = "piiktv"
    private const val KEY_LANGUAGE = "language"
}
