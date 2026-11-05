package it.mbs.piiktv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import it.mbs.piiktv.net.PiikHttp
import it.mbs.piiktv.rtc.DecoderStats
import it.mbs.piiktv.rtc.VideoSurface
import it.mbs.piiktv.session.ViewerSession
import it.mbs.piiktv.ui.AspectFrame
import it.mbs.piiktv.ui.Ui

/**
 * Player a schermo intero. Ottimizzazioni attive:
 *  - SurfaceView con il decoder hardware che rende direttamente sulla Surface (overlay, nessuna GPU);
 *  - buffer della Surface alla risoluzione del video: lo scaling lo fa lo scaler hardware dello schermo;
 *  - "minimal post-processing" (ALLM): la TV passa in modalità Gioco, niente elaborazioni d'immagine lente;
 *  - frequenza della Surface fissata a 60 Hz, modalità display a 60 Hz;
 *  - Wi-Fi in modalità bassa latenza (niente risparmio energetico/scansioni durante la visione);
 *  - prestazioni sostenute e schermo sempre acceso.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback, ViewerSession.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var frame: AspectFrame
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private var session: ViewerSession? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statsVisible = false
    private var finishing = false

    private val statsTick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (statsVisible) s.stats { text -> main.post { if (statsVisible) statsView.text = text } }
            main.postDelayed(this, 1000)
        }
    }

    // Lingua scelta nelle impostazioni (con "automatica" il Context resta quello di sistema).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setupWindow()
        buildUi()
        acquireWifiLock()
        statsVisible = Prefs.showStats
        statsView.visibility = if (statsVisible) View.VISIBLE else View.GONE

        VideoSurface.onVideoSize = { w, h ->
            main.post {
                frame.setAspect(w, h)
                surfaceView.holder.setFixedSize(w, h)
            }
        }
        DecoderStats.reset()
        it.mbs.piiktv.rtc.SmoothStats.reset()
        try {
            @Suppress("DEPRECATION")
            VideoSurface.refreshHz = windowManager.defaultDisplay.refreshRate
        } catch (_: Exception) {
        }

        val host = intent.getStringExtra(EXTRA_HOST) ?: run { finish(); return }
        val port = intent.getIntExtra(EXTRA_PORT, PiikHttp.DEFAULT_PORT)
        val room = intent.getStringExtra(EXTRA_ROOM) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: host
        showStatus(getString(R.string.player_connecting, name, room))
        session = ViewerSession(
            applicationContext,
            ViewerSession.JoinParams(
                host = host,
                port = port,
                secure = intent.getBooleanExtra(EXTRA_SECURE, false),
                roomId = room,
                viewerGrant = intent.getStringExtra(EXTRA_GRANT),
                viewerPassword = Prefs.viewerPasswordFor(host, room),
                siteCookie = intent.getStringExtra(EXTRA_COOKIE),
                displayName = Prefs.displayName,
                clientId = Prefs.clientIdFor(host, room),
                latencyMode = Prefs.latencyMode,
                audioEnabled = Prefs.audioEnabled,
                avSync = Prefs.avSync,
            ),
            this,
        ).also { it.start() }
        main.postDelayed(statsTick, 1000)
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        val attrs = window.attributes
        if (Build.VERSION.SDK_INT >= 30) {
            attrs.preferMinimalPostProcessing = true
        }
        // Modalità display: la più alta risoluzione a ~60 Hz (Display.Mode esiste da Android 6.0)
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                @Suppress("DEPRECATION")
                val d = windowManager.defaultDisplay
                val best = d.supportedModes
                    .filter { kotlin.math.abs(it.refreshRate - 60f) < 1.5f }
                    .maxByOrNull { it.physicalWidth * it.physicalHeight }
                if (best != null) attrs.preferredDisplayModeId = best.modeId
            } catch (e: Exception) {
                Log.w(TAG, "display mode: $e")
            }
        }
        window.attributes = attrs
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 24 && pm.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        } catch (_: Exception) {
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame = AspectFrame(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        frame.addView(surfaceView)
        root.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            val p = Ui.dp(this@PlayerActivity, 24f)
            setPadding(p, p, p, p)
            background = Ui.rounded(0xCC100C1E.toInt(), Ui.dp(this@PlayerActivity, 16f).toFloat())
        }
        root.addView(statusView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        statsView = TextView(this).apply {
            setTextColor(0xFFE8FFE8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            val p = Ui.dp(this@PlayerActivity, 12f)
            setPadding(p, p, p, p)
            background = Ui.rounded(0xB0000000.toInt(), Ui.dp(this@PlayerActivity, 10f).toFloat())
            text = getString(R.string.player_stats_placeholder)
        }
        val slp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        slp.setMargins(Ui.dp(this, 20f), Ui.dp(this, 20f), 0, 0)
        root.addView(statsView, slp)
        setContentView(root)
    }

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "PiikTV:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock: $e")
        }
    }

    private fun showStatus(text: String?) {
        if (text == null) {
            statusView.visibility = View.GONE
        } else {
            statusView.text = text
            statusView.visibility = View.VISIBLE
        }
    }

    // --- Surface ---------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (_: Exception) {
            }
        }
        VideoSurface.set(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        VideoSurface.set(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VideoSurface.set(null)
    }

    // --- Sessione ----------------------------------------------------------------------------

    override fun onStatus(text: String) = main.post { showStatus(text) }.let { }

    override fun onFirstFrame() = main.post { showStatus(null) }.let { }

    override fun onFatal(code: String?, message: String) {
        main.post {
            if (finishing) return@post
            finishing = true
            val data = Intent()
                .putExtra(EXTRA_HOST, intent.getStringExtra(EXTRA_HOST))
                .putExtra(EXTRA_PORT, intent.getIntExtra(EXTRA_PORT, PiikHttp.DEFAULT_PORT))
                .putExtra(EXTRA_SECURE, intent.getBooleanExtra(EXTRA_SECURE, false))
                .putExtra(RESULT_ERROR_CODE, code)
                .putExtra(RESULT_MESSAGE, message)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    // --- Telecomando -------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_A -> {
                statsVisible = !statsVisible
                statsView.visibility = if (statsVisible) View.VISIBLE else View.GONE
                if (statsVisible) session?.stats { t -> main.post { statsView.text = t } }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                session?.requestKeyFrame()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        // Indietro = chiudi la visione e torna al menu dell'app (che non si ricollega da sola).
        if (!finishing) {
            finishing = true
            setResult(RESULT_OK, Intent().putExtra(RESULT_BACK, true))
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        // Ricevitore dei link attivo anche durante la visione: un link nuovo apre l'altra stanza.
        it.mbs.piiktv.net.TvLinkServer.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        it.mbs.piiktv.net.TvLinkServer.onStop()
        // Uscendo dall'app (Home) si chiude la sessione: niente rete/decoder in background.
        if (!isChangingConfigurations) {
            teardown()
            if (!isFinishing) finish()
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        main.removeCallbacks(statsTick)
        session?.stop()
        session = null
        VideoSurface.onVideoSize = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    companion object {
        private const val TAG = "PiikPlayer"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECURE = "secure"
        const val EXTRA_NAME = "name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_GRANT = "grant"
        const val EXTRA_COOKIE = "cookie"
        const val RESULT_ERROR_CODE = "errorCode"
        const val RESULT_MESSAGE = "message"
        const val RESULT_BACK = "back"
    }
}
