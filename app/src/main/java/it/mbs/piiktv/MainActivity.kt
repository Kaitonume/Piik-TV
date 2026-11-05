package it.mbs.piiktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.mbs.piiktv.net.Discovery
import it.mbs.piiktv.net.PiikHttp
import it.mbs.piiktv.net.PiikServer
import it.mbs.piiktv.ui.Ui

/** Schermata iniziale: PC con Piik trovati in rete, codice stanza, impostazioni. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val servers = LinkedHashMap<String, PiikServer>()
    private var discovery: Discovery? = null
    private var autoConnectTried = false
    /** Lingua appena cambiata: l'attività viene ricreata e riapre le impostazioni. */
    private var reopenSettings = false

    // Lingua scelta nelle impostazioni (con "automatica" il Context resta quello di sistema).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        buildUi()
        autoConnectTried = savedInstanceState?.getBoolean("auto") ?: false
        launchHandled = savedInstanceState?.getBoolean("launch") ?: false
        startDiscovery()
        if (!launchHandled) {
            launchHandled = true
            handleLaunchIntent(intent)
        }
        if (savedInstanceState?.getBoolean("settings") == true) showSettings()
    }

    private var launchHandled = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.dataString == null &&
            intent.getStringExtra("host") == null
        if (fromLauncher) {
            // Rientro nell'app dalla schermata principale della TV: come all'avvio, ci si ricollega
            // da soli all'ultimo PC (lo fa onResume, una volta sola). Tornando invece dalla visione
            // con Indietro si resta nel menu.
            reconnectOnResume = true
        } else {
            // Link arrivato (ricevitore della TV o intent): ha la precedenza sulla riconnessione
            // automatica, anche se la visione in corso è appena stata chiusa per aprirlo.
            reconnectOnResume = false
            manualDialog?.dismiss()
            handleLaunchIntent(intent)
        }
    }

    /**
     * Avvio diretto: link di invito Piik (http://pc:8787/r/1234#v=…) come dato dell'intent, oppure
     * extra "host" + "room" (ed eventualmente "grant"), utile anche per l'automazione via adb.
     */
    private fun handleLaunchIntent(i: Intent?) {
        if (i == null) return
        val data = i.dataString
        val host = i.getStringExtra("host")
        val room = i.getStringExtra("room")
        val grant = i.getStringExtra("grant")
        // Parametri di test opzionali (adb): decoder VP8 e modalità latenza
        if (i.hasExtra("vp8")) Prefs.vp8Decoder = i.getIntExtra("vp8", 0)
        if (i.hasExtra("latency")) Prefs.latencyMode = i.getIntExtra("latency", Prefs.LATENCY_BALANCED)
        when {
            data != null && data.contains("/r/") -> { autoConnectTried = true; handleManual(data) }
            host != null && room != null -> {
                autoConnectTried = true
                handleManual("http://$host:${i.getIntExtra("port", PiikHttp.DEFAULT_PORT)}/r/$room" + (grant?.let { "#v=$it" } ?: ""))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("auto", autoConnectTried)
        outState.putBoolean("launch", launchHandled)
        outState.putBoolean("settings", reopenSettings)
    }

    override fun onDestroy() {
        discovery?.cancel()
        super.onDestroy()
    }

    // Rientro nell'app: il launcher di solito riporta in primo piano il task esistente senza
    // consegnare un nuovo intent, quindi l'uscita (Home) la si riconosce qui.
    private var playerOpen = false
    private var reconnectOnResume = false

    override fun onStart() {
        super.onStart()
        it.mbs.piiktv.net.TvLinkServer.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        it.mbs.piiktv.net.TvLinkServer.onStop()
        // Uscita dal menu (Home, altra app): al rientro ci si ricollega da soli come all'avvio.
        if (!playerOpen && !isChangingConfigurations) reconnectOnResume = true
    }

    override fun onResume() {
        super.onResume()
        if (reconnectOnResume) {
            reconnectOnResume = false
            if (Prefs.autoConnect && !playerOpen) {
                autoConnectTried = false
                startDiscovery()
            }
        }
    }

    private fun buildUi() {
        val pad = Ui.dp(this, 48f)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = Ui.vertical(this).apply { setPadding(pad, Ui.dp(this@MainActivity, 36f), pad, pad) }
        col.addView(Ui.text(this, getString(R.string.app_name), 40f, bold = true))
        col.addView(Ui.text(this, getString(R.string.main_subtitle), 18f, Ui.TEXT_DIM))
        status = Ui.text(this, "", 18f, Ui.WARN).apply { setPadding(0, Ui.dp(this@MainActivity, 24f), 0, Ui.dp(this@MainActivity, 8f)) }
        col.addView(status)
        list = Ui.vertical(this)
        col.addView(list, Ui.lp())

        val actions = Ui.horizontal(this)
        val gap = Ui.dp(this, 16f)
        actions.addView(Ui.button(this, getString(R.string.search_again)) { startDiscovery() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, end = gap, weight = 1f))
        actions.addView(Ui.button(this, getString(R.string.address_or_link)) { askManual() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, end = gap, weight = 1f))
        actions.addView(Ui.button(this, getString(R.string.settings)) { showSettings() }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        col.addView(actions, Ui.lp(top = Ui.dp(this, 28f)))

        col.addView(
            Ui.text(
                this,
                getString(R.string.main_help),
                15f, Ui.TEXT_DIM,
            ).apply { setPadding(0, Ui.dp(this@MainActivity, 28f), 0, 0) }
        )
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
    }

    private fun startDiscovery() {
        discovery?.cancel()
        servers.clear()
        renderList()
        status.text = getString(R.string.status_searching)
        val priority = LinkedHashSet<String>().apply {
            Prefs.lastHost?.takeIf { !isPublicHost(it) }?.let { add(it) }
            addAll(Prefs.knownHosts.filter { !isPublicHost(it) })
        }
        // Ultimo link pubblico (tunnel di Piik o sito Piik): non è nella rete di casa, la ricerca in LAN
        // non lo trova; lo si interroga direttamente (finché il link è valido ci si ricollega da soli).
        val last = Prefs.lastHost
        if (last != null && isPublicHost(last)) {
            val generation = ++publicProbeGeneration
            Thread {
                val s = PiikHttp.probe(last, Prefs.portFor(last))
                main.post { if (s != null && generation == publicProbeGeneration) onServerFound(s) }
            }.start()
        }
        discovery = Discovery(
            onFound = { s -> main.post { onServerFound(s) } },
            onProgress = { d, t -> main.post { if (servers.isEmpty()) status.text = getString(R.string.status_searching_progress, d, t) } },
            onFinished = { n ->
                main.post {
                    status.text = if (n == 0) getString(R.string.status_none_found)
                    else if (n == 1) getString(R.string.status_found_one) else getString(R.string.status_found_many, n)
                }
            },
        ).also { it.start(priority) }
    }

    private var publicProbeGeneration = 0

    private fun isPublicHost(host: String) = host.any { it.isLetter() }

    private fun onServerFound(s: PiikServer) {
        s.name?.let { Prefs.setHostName(s.host, it) } ?: run { s.name = Prefs.hostName(s.host) }
        servers[s.host] = s
        // Solo gli IP di casa: gli indirizzi dei tunnel pubblici cambiano a ogni avvio di Piik.
        if (!s.isPublic) Prefs.knownHosts = Prefs.knownHosts + s.host
        status.text = if (servers.size == 1) getString(R.string.status_found_one) else getString(R.string.status_found_many, servers.size)
        renderList()
        maybeAutoConnect(s)
    }

    private fun maybeAutoConnect(s: PiikServer) {
        if (autoConnectTried || !Prefs.autoConnect) return
        if (s.host != Prefs.lastHost) return
        val room = Prefs.roomFor(s.host) ?: return
        autoConnectTried = true
        Toast.makeText(this, getString(R.string.toast_auto_connect, s.name ?: s.host, room), Toast.LENGTH_SHORT).show()
        connect(s, room)
    }

    private fun renderList() {
        list.removeAllViews()
        val gap = Ui.dp(this, 14f)
        var first: View? = null
        for (s in servers.values) {
            val row = Ui.horizontal(this)
            val room = Prefs.roomFor(s.host)
            val title = s.name ?: getString(R.string.server_default_name)
            val sub = buildString {
                append(s.host)
                append(if (room != null) getString(R.string.server_room, room) else getString(R.string.server_enter_code))
                if (s.linkMode) append(getString(R.string.server_link_mode))
            }
            val b = Ui.button(this, "$title\n$sub", 21f) {
                if (room != null) connect(s, room) else askCode(s)
            }
            row.addView(b, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, end = gap, weight = 1f))
            row.addView(Ui.button(this, getString(R.string.button_code), 19f) { askCode(s) }.apply { gravity = Gravity.CENTER },
                Ui.lp(Ui.dp(this, 180f), ViewGroup.LayoutParams.MATCH_PARENT))
            list.addView(row, Ui.lp(top = gap))
            if (first == null) first = b
        }
        first?.requestFocus()
    }

    // Tema scuro DeviceDefault dei dialoghi: pubblico da Android 5.1 (API 22); su 5.0 la costante di
    // AlertDialog porta allo stesso stile (allora interno al sistema).
    private fun dialog() = AlertDialog.Builder(
        this,
        if (Build.VERSION.SDK_INT >= 22) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else @Suppress("DEPRECATION") AlertDialog.THEME_DEVICE_DEFAULT_DARK,
    )

    private fun askCode(s: PiikServer, message: String? = null) {
        val box = Ui.vertical(this).apply { val p = Ui.dp(this@MainActivity, 24f); setPadding(p, p / 2, p, 0) }
        val code = Ui.input(this, getString(R.string.hint_room_code), InputType.TYPE_CLASS_NUMBER, Prefs.roomFor(s.host)).apply {
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val pw = Ui.input(this, getString(R.string.hint_room_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(code, Ui.lp())
        box.addView(pw, Ui.lp(top = Ui.dp(this, 12f)))
        dialog().setTitle(getString(R.string.dialog_room_title, s.name ?: s.host))
            .setMessage(message ?: getString(R.string.dialog_room_message))
            .setView(box)
            .setPositiveButton(R.string.button_watch) { _, _ ->
                val c = code.text.toString().trim()
                if (!Regex("^[1-9][0-9]{3}$").matches(c)) {
                    askCode(s, getString(R.string.error_code_format))
                    return@setPositiveButton
                }
                Prefs.setRoomFor(s.host, c)
                Prefs.setViewerPasswordFor(s.host, c, pw.text.toString().takeIf { it.isNotEmpty() })
                renderList()
                connect(s, c)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askManual() {
        val input = Ui.input(this, getString(R.string.hint_address), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val box = FrameLayout(this).apply { val p = Ui.dp(this@MainActivity, 24f); setPadding(p, p / 2, p, 0); addView(input) }
        // Il link si può anche inviare dal PC: pulsante «Manda URL all'app PIIK TV» in Piik, oppure
        // la pagina del ricevitore della TV (attivo finché l'app è aperta).
        val tvIp = Discovery.localIpv4().firstOrNull()?.first?.hostAddress
        val port = it.mbs.piiktv.net.TvLinkServer.port
        // La porta passa come testo: in un URL le cifre non devono seguire la lingua (%d userebbe
        // ad es. le cifre arabo-indiane con una TV in arabo).
        val sendHint = if (tvIp != null && port > 0) {
            getString(R.string.manual_send_hint, tvIp, port.toString())
        } else null
        manualDialog = dialog().setTitle(R.string.dialog_manual_title)
            .apply { if (sendHint != null) setMessage(sendHint) }
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> handleManual(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { manualDialog = null }
            .show()
    }

    private var manualDialog: AlertDialog? = null

    private fun handleManual(raw: String) {
        if (raw.isEmpty()) return
        val link = Regex("^(https?)://([^/:#?]+)(?::(\\d+))?/r/([1-9][0-9]{3})(?:[^#]*)(?:#(.*))?$").find(raw)
        val host: String
        val port: Int
        var room: String? = null
        var grant: String? = null
        if (link != null) {
            val https = link.groupValues[1] == "https"
            host = link.groupValues[2]
            // Senza porta: quella dello schema (link pubblico https://….trycloudflare.com = 443).
            port = link.groupValues[3].toIntOrNull() ?: if (https) 443 else 80
            room = link.groupValues[4]
            grant = Regex("(?:^|&)v=([A-Za-z0-9_-]{22})").find(link.groupValues[5])?.groupValues?.get(1)
        } else {
            // Indirizzo senza stanza: un IP di casa usa la porta di Piik (8787), un nome di dominio
            // (sito o tunnel pubblico) quella dello schema.
            val hp = raw.removePrefix("http://").removePrefix("https://").substringBefore('/')
            host = hp.substringBefore(':')
            port = hp.substringAfter(':', "").toIntOrNull()
                ?: if (host.any { it.isLetter() }) (if (raw.startsWith("http://")) 80 else 443)
                else PiikHttp.DEFAULT_PORT
        }
        status.text = getString(R.string.status_checking, host)
        Thread {
            val s = PiikHttp.probe(host, port)
            main.post {
                if (s == null) {
                    status.text = getString(R.string.status_not_responding, host, port.toString())
                    return@post
                }
                onServerFound(s)
                if (room != null) {
                    Prefs.setRoomFor(host, room)
                    if (grant != null) Prefs.setGrantFor(host, room, grant)
                    renderList()
                    connect(s, room)
                } else {
                    askCode(s)
                }
            }
        }.start()
    }

    /** Verifica l'accesso al sito (password di piik-app, se impostata) e avvia il player. */
    private fun connect(s: PiikServer, room: String, sitePasswordPrompt: String? = null) {
        val grant = Prefs.grantFor(s.host, room)
        status.text = getString(R.string.status_connecting, s.name ?: s.host)
        Thread {
            var cookie: String? = null
            if (grant == null) {
                val access = PiikHttp.siteAccess(s, null)
                if (access != null && access.required && !access.authenticated) {
                    val saved = Prefs.sitePasswordFor(s.host)
                    if (saved == null || sitePasswordPrompt != null) {
                        main.post { askSitePassword(s, room, sitePasswordPrompt) }
                        return@Thread
                    }
                    when (val r = PiikHttp.login(s, saved)) {
                        is PiikHttp.LoginResult.Ok -> cookie = r.cookie
                        PiikHttp.LoginResult.WrongPassword -> {
                            main.post { askSitePassword(s, room, getString(R.string.error_wrong_password)) }
                            return@Thread
                        }
                        is PiikHttp.LoginResult.Failed -> {
                            main.post { status.text = getString(R.string.status_access_failed, r.reason) }
                            return@Thread
                        }
                    }
                }
            }
            main.post { launchPlayer(s, room, grant, cookie) }
        }.start()
    }

    private fun askSitePassword(s: PiikServer, room: String, message: String?) {
        val pw = Ui.input(this, getString(R.string.hint_site_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val box = FrameLayout(this).apply { val p = Ui.dp(this@MainActivity, 24f); setPadding(p, p / 2, p, 0); addView(pw) }
        dialog().setTitle(getString(R.string.dialog_site_password_title, s.name ?: s.host))
            .setMessage(message ?: getString(R.string.dialog_site_password_message))
            .setView(box)
            .setPositiveButton(R.string.button_sign_in) { _, _ ->
                Prefs.setSitePasswordFor(s.host, pw.text.toString())
                connect(s, room)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> status.text = "" }
            .show()
    }

    private fun launchPlayer(s: PiikServer, room: String, grant: String?, cookie: String?) {
        Prefs.rememberHost(s.host)
        Prefs.setEndpointFor(s.host, s.port, s.secure)
        status.text = ""
        val i = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_HOST, s.host)
            .putExtra(PlayerActivity.EXTRA_PORT, s.port)
            .putExtra(PlayerActivity.EXTRA_SECURE, s.secure)
            .putExtra(PlayerActivity.EXTRA_NAME, s.name)
            .putExtra(PlayerActivity.EXTRA_ROOM, room)
            .putExtra(PlayerActivity.EXTRA_GRANT, grant)
            .putExtra(PlayerActivity.EXTRA_COOKIE, cookie)
        playerOpen = true
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PLAYER)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PLAYER) return
        playerOpen = false
        // Visione chiusa con Indietro: si resta nel menu. Chiusa perché l'app è andata in secondo
        // piano (Home): al rientro ci si ricollega da soli.
        if (data == null) {
            reconnectOnResume = true
            return
        }
        if (data.getBooleanExtra(PlayerActivity.RESULT_BACK, false)) return
        val host = data.getStringExtra(PlayerActivity.EXTRA_HOST) ?: return
        val error = data.getStringExtra(PlayerActivity.RESULT_ERROR_CODE)
        val msg = data.getStringExtra(PlayerActivity.RESULT_MESSAGE)
        val s = servers[host] ?: PiikServer(
            host, data.getIntExtra(PlayerActivity.EXTRA_PORT, PiikHttp.DEFAULT_PORT),
            secure = data.getBooleanExtra(PlayerActivity.EXTRA_SECURE, false), name = Prefs.hostName(host),
        )
        when (error) {
            "ROOM_NOT_FOUND", "ROOM_ACCESS_DENIED", "INVALID_TOKEN" -> {
                Prefs.roomFor(host)?.let { Prefs.setGrantFor(host, it, null) }
                askCode(s, msg ?: getString(R.string.error_room_not_found_short))
            }
            "AUTH_REQUIRED" -> {
                val room = Prefs.roomFor(host)
                if (room != null) connect(s, room, msg ?: getString(R.string.error_password_required)) else askCode(s)
            }
            null -> if (msg != null) status.text = msg
            else -> status.text = msg ?: error
        }
        renderList()
    }

    private fun showSettings() {
        val latencyNames = arrayOf(getString(R.string.latency_min), getString(R.string.latency_balanced), getString(R.string.latency_smooth))
        val yes = getString(R.string.yes)
        val no = getString(R.string.no)
        val items = arrayOf(
            getString(R.string.settings_latency, latencyNames[Prefs.latencyMode]),
            getString(R.string.settings_audio, getString(if (Prefs.audioEnabled) R.string.audio_on else R.string.audio_off)),
            getString(R.string.settings_stats, if (Prefs.showStats) yes else no),
            getString(R.string.settings_av_sync, getString(if (Prefs.avSync) R.string.av_sync_on else R.string.av_sync_off)),
            getString(R.string.settings_auto_connect, if (Prefs.autoConnect) yes else no),
            getString(R.string.settings_tv_name, Prefs.displayName),
            getString(R.string.settings_vp8, getString(if (Prefs.vp8Decoder == 2) R.string.vp8_software else R.string.vp8_hardware)),
            getString(R.string.settings_language, languageName(Prefs.language)),
        )
        dialog().setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> dialog().setTitle(R.string.dialog_latency_title)
                        .setSingleChoiceItems(latencyNames, Prefs.latencyMode) { d, w -> Prefs.latencyMode = w; d.dismiss(); showSettings() }
                        .show()
                    1 -> { Prefs.audioEnabled = !Prefs.audioEnabled; showSettings() }
                    2 -> { Prefs.showStats = !Prefs.showStats; showSettings() }
                    3 -> { Prefs.avSync = !Prefs.avSync; showSettings() }
                    4 -> { Prefs.autoConnect = !Prefs.autoConnect; showSettings() }
                    5 -> askDisplayName()
                    6 -> { Prefs.vp8Decoder = if (Prefs.vp8Decoder == 2) 0 else 2; showSettings() }
                    7 -> askLanguage()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
            .apply { compactItems(listView) }
    }

    /**
     * Con la voce «Language / Lingua» le impostazioni sono 8: alla misura standard (48 dp) sulla TV
     * non entravano più insieme al tasto Chiudi, che restava tagliato. Le voci restano quelle del
     * dialogo (stesso layout, testo e margini); cambia solo l'altezza minima, 44 dp. Va fatto dopo
     * show(): l'AlertDialog collega l'elenco al suo adapter solo quando viene mostrato.
     */
    private fun compactItems(list: android.widget.ListView?) {
        val items = list?.adapter ?: return
        val minHeight = Ui.dp(this, 44f)
        list.adapter = object : android.widget.ListAdapter by items {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                items.getView(position, convertView, parent).also { item ->
                    item.minimumHeight = minHeight
                    (item as? TextView)?.minHeight = minHeight
                }
        }
    }

    private fun languageName(tag: String) = getString(
        when (tag) {
            AppLocale.ENGLISH -> R.string.language_english
            AppLocale.ITALIAN -> R.string.language_italian
            AppLocale.CHINESE -> R.string.language_chinese
            else -> R.string.language_auto
        }
    )

    /** Automatica (lingua di sistema), English, Italiano: la scelta vale subito (attività ricreata). */
    private fun askLanguage() {
        val tags = arrayOf(AppLocale.AUTO, AppLocale.ENGLISH, AppLocale.ITALIAN, AppLocale.CHINESE)
        val names = Array(tags.size) { languageName(tags[it]) }
        val current = tags.indexOf(Prefs.language).coerceAtLeast(0)
        dialog().setTitle(R.string.language_title)
            .setSingleChoiceItems(names, current) { d, w ->
                d.dismiss()
                if (w == current) {
                    showSettings()
                } else {
                    Prefs.language = tags[w]
                    reopenSettings = true
                    recreate()
                }
            }
            .show()
    }

    private fun askDisplayName() {
        val input = Ui.input(this, getString(R.string.hint_tv_name), InputType.TYPE_CLASS_TEXT, Prefs.displayName).apply {
            filters = arrayOf(InputFilter.LengthFilter(24))
        }
        val box = FrameLayout(this).apply { val p = Ui.dp(this@MainActivity, 24f); setPadding(p, p / 2, p, 0); addView(input) }
        dialog().setTitle(R.string.dialog_tv_name_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> Prefs.displayName = input.text.toString(); showSettings() }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettings() }
            .show()
    }

    companion object {
        private const val REQ_PLAYER = 7
    }
}
