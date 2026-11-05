package it.mbs.piiktv.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import it.mbs.piiktv.AppLocale
import it.mbs.piiktv.R
import it.mbs.piiktv.rtc.RtcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sessione di visione Piik (protocollo piik-v24) come "foglia": riceve dal PC, non ritrasmette.
 *
 * Flusso: WebSocket /signal -> authenticate -> relay-capacity 0 -> route-update prepare ->
 * offerta SDP del PC -> risposta -> ICE -> route-transport-connected -> primo fotogramma decodificato ->
 * route-ready -> route-update active (commit).
 *
 * Tutta la logica gira su un unico thread ("piik-session"), come fa il client web di Piik.
 */
class ViewerSession(private val ctx: Context, val params: JoinParams, private val listener: Listener) {

    data class JoinParams(
        val host: String,
        val port: Int,
        val secure: Boolean,
        val roomId: String,
        val viewerGrant: String?,
        val viewerPassword: String?,
        val siteCookie: String?,
        val displayName: String,
        val clientId: String,
        val latencyMode: Int,
        val audioEnabled: Boolean,
        val avSync: Boolean,
    )

    interface Listener {
        fun onStatus(text: String)
        fun onFirstFrame()
        fun onFatal(code: String?, message: String)
    }

    private val tag = "PiikSession"
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "piik-session") }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }
    private var factory: PeerConnectionFactory? = null
    private var sig: SignalingClient? = null
    @Volatile private var stopped = false
    private var fatalSent = false

    // Stato del protocollo
    private var authenticated = false
    private var peerId: String? = null
    private var fence = -1L
    private val seenRevisionPhase = HashSet<String>()
    private var lastActiveRevision = -1L
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var active: Edge? = null
    private var pending: Edge? = null
    private var expectedReplacementFrom: String? = null
    private val queuedSignals = ArrayList<Pair<String, JSONObject>>()
    private var hostOnline = true
    private var hostPaused = false
    private var sharing = true
    private var lastError: Pair<String, String>? = null

    // Riconnessione e keepalive
    private var reconnectAttempt = 0
    private var reconnectTask: ScheduledFuture<*>? = null
    private var authTimeout: ScheduledFuture<*>? = null
    private var challengeTask: ScheduledFuture<*>? = null
    private var challengeSeq = 0L
    private var lastChallengeAckAt = 0L
    private var exhaustedTask: ScheduledFuture<*>? = null
    private var everPlayed = false

    /** Testi per lo schermo nella lingua scelta nell'app ([ctx] è il Context dell'applicazione). */
    private val strings: Context = AppLocale.wrap(ctx)
    private val stats = StatsFormatter(strings)

    /** Un collegamento media con un genitore (il PC o un altro spettatore). */
    private inner class Edge(
        val revision: Long,
        val parentPeerId: String,
        val connectionId: String,
        val isReplacement: Boolean,
    ) {
        var pc: PeerConnection? = null
        var remoteSet = false
        val pendingCandidates = ArrayList<IceCandidate>()
        var transportConnectedSent = false
        var readySent = isReplacement
        var firstFrame = false
        var closed = false
        var connected = false
        var negotiating = false
        val queuedOffers = ArrayDeque<String>()
        var disconnectTask: ScheduledFuture<*>? = null
        var recoveryTask: ScheduledFuture<*>? = null
        var proofTask: ScheduledFuture<*>? = null
        var recoveryStep = 0
        override fun toString() = "Edge(rev=$revision parent=$parentPeerId cid=$connectionId repl=$isReplacement)"
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo di vita
    // ---------------------------------------------------------------------------------------

    fun start() {
        post {
            try {
                factory = RtcEngine.createFactory(ctx, params.latencyMode)
            } catch (e: Throwable) {
                Log.e(tag, "factory: $e")
                fatal("WEBRTC", strings.getString(R.string.session_webrtc_init_failed, e.message))
                return@post
            }
            connectSignal()
        }
    }

    /**
     * Chiude la sessione: segnalazione, PeerConnection (quindi audio e video) e factory.
     *
     * L'executor va spento solo DOPO la chiusura, dall'interno del compito stesso: con
     * executeExistingDelayedTasksAfterShutdownPolicy = false, shutdown() cancella anche i compiti
     * immediati ancora in coda, quindi spegnerlo subito dopo execute() annullava quasi sempre la
     * chiusura (la PeerConnection restava viva: audio senza schermata). E i callback che WebRTC
     * chiama durante pc.close() devono poter ancora accodare (vengono poi scartati) senza eccezioni.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        Log.i(tag, "stop requested")
        post {
            try {
                sig?.close()
                sig = null
                closeAllEdges()
                factory?.dispose()
                factory = null
                Log.i(tag, "stopped")
            } catch (e: Throwable) {
                Log.w(tag, "stop: $e", e)
            } finally {
                exec.shutdown()
            }
        }
    }

    /**
     * Esegue [block] sul thread della sessione. Dopo la chiusura il compito viene ignorato senza
     * eccezioni: un'eccezione dentro un callback di WebRTC (thread nativo) fa abortire l'intera app.
     */
    private fun post(block: () -> Unit) {
        try {
            exec.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    Log.e(tag, "session task: $e", e)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Come [post] ma ritardato; null se la sessione è già chiusa. */
    private fun later(delay: Long, unit: TimeUnit, block: () -> Unit): ScheduledFuture<*>? =
        try {
            exec.schedule({
                try {
                    block()
                } catch (e: Throwable) {
                    Log.e(tag, "session task: $e", e)
                }
            }, delay, unit)
        } catch (_: RejectedExecutionException) {
            null
        }

    fun requestKeyFrame() {
        it.mbs.piiktv.rtc.DecoderStats.keyFrameRequest = true
    }

    fun stats(cb: (String) -> Unit) {
        if (stopped) return
        post {
            val e = active ?: pending
            val pc = e?.pc
            val route = when {
                e == null -> strings.getString(R.string.route_none)
                e.parentPeerId.isNotEmpty() -> strings.getString(R.string.route_p2p)
                else -> "—"
            }
            if (pc == null) {
                cb(stats.format(null, params.latencyMode, route))
                return@post
            }
            pc.getStats { report: RTCStatsReport ->
                if (!stopped) post { cb(stats.format(report, params.latencyMode, route)) }
            }
        }
    }

    private fun status(text: String) {
        if (!stopped) listener.onStatus(text)
    }

    private fun fatal(code: String?, message: String) {
        if (fatalSent || stopped) return
        fatalSent = true
        Log.w(tag, "fatal $code: $message")
        listener.onFatal(code, message)
    }

    // ---------------------------------------------------------------------------------------
    // Segnalazione
    // ---------------------------------------------------------------------------------------

    private fun connectSignal() {
        if (stopped) return
        authenticated = false
        val server = it.mbs.piiktv.net.PiikServer(params.host, params.port, secure = params.secure)
        val client = SignalingClient(server.signalUrl, server.origin, params.siteCookie, object : SignalingClient.Events {
            override fun onOpen(client: SignalingClient) {
                post { if (client === sig) sendAuthenticate() }
            }

            override fun onMessage(client: SignalingClient, msg: JSONObject) {
                post {
                    if (client !== sig || stopped) return@post
                    try {
                        handleMessage(msg)
                    } catch (e: Exception) {
                        Log.e(tag, "handle ${msg.optString("type")}: $e", e)
                    }
                }
            }

            override fun onClosed(client: SignalingClient, code: Int, reason: String, failure: Throwable?) {
                post { if (client === sig) onSignalClosed(code, reason) }
            }
        })
        sig = client
        client.connect()
        authTimeout?.cancel(false)
        authTimeout = exec.schedule({
            if (!authenticated && sig === client) {
                Log.w(tag, "auth timeout")
                client.close()
                onSignalClosed(1006, "auth timeout")
            }
        }, 8, TimeUnit.SECONDS)
    }

    private fun send(msg: JSONObject) {
        val s = sig ?: return
        if (!s.send(msg)) Log.w(tag, "send failed: ${msg.optString("type")}")
    }

    private fun sendAuthenticate() {
        val m = JSONObject()
            .put("type", "authenticate")
            .put("protocol", PROTOCOL)
            .put("roomId", params.roomId)
            .put("role", "viewer")
            .put("clientId", params.clientId)
        if (params.viewerGrant != null) m.put("viewerGrant", params.viewerGrant)
        else if (!params.viewerPassword.isNullOrEmpty()) m.put("viewerPassword", params.viewerPassword)
        m.put("displayName", params.displayName)
        m.put("routePreference", "p2p")
        send(m)
    }

    private fun onSignalClosed(code: Int, reason: String) {
        if (stopped) return
        Log.i(tag, "signal closed $code $reason")
        sig = null
        authenticated = false
        challengeTask?.cancel(false)
        authTimeout?.cancel(false)
        val err = lastError
        when (code) {
            4001 -> fatal("SESSION_REPLACED", strings.getString(R.string.session_replaced))
            4003 -> fatal(err?.first ?: "AUTH_FAILED", err?.second ?: strings.getString(R.string.session_auth_failed))
            4004 -> fatal("ACCESS_REVOKED", strings.getString(R.string.session_access_revoked))
            1008 -> fatal("PROTOCOL", strings.getString(R.string.session_protocol))
            4403 -> fatal("ORIGIN", strings.getString(R.string.session_origin))
            else -> scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped || fatalSent) return
        reconnectTask?.cancel(false)
        val base = longArrayOf(500, 1000, 2000, 4000, 8000)[reconnectAttempt.coerceAtMost(4)]
        reconnectAttempt++
        val delay = base + Random.nextLong(0, 250)
        if (active == null || active?.connected != true) status(strings.getString(R.string.session_reconnecting))
        reconnectTask = exec.schedule({ connectSignal() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun startChallenges() {
        challengeTask?.cancel(false)
        lastChallengeAckAt = SystemClock.elapsedRealtime()
        challengeTask = exec.scheduleWithFixedDelay({
            val s = sig ?: return@scheduleWithFixedDelay
            if (!authenticated) return@scheduleWithFixedDelay
            if (SystemClock.elapsedRealtime() - lastChallengeAckAt > 13_000) {
                Log.w(tag, "signaling liveness lost")
                s.close()
                onSignalClosed(4002, "liveness")
                return@scheduleWithFixedDelay
            }
            challengeSeq++
            send(JSONObject().put("type", "signaling-challenge").put("sequence", challengeSeq))
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun handleMessage(m: JSONObject) {
        val type = m.optString("type")
        if (type != "signal" && type != "signaling-challenge-response") Log.i(tag, "<- ${m.toString().take(400)}")
        when (type) {
            "authenticated" -> onAuthenticated(m)
            "error" -> onError(m)
            "route-update" -> onRouteUpdate(m)
            "route-status" -> onRouteStatus(m)
            "signal" -> onSignal(m.optString("fromPeerId"), m.optJSONObject("payload"))
            "signaling-challenge-response" -> lastChallengeAckAt = SystemClock.elapsedRealtime()
            "sharing-stopped" -> {
                closeAllEdges()
                fence = -1
                seenRevisionPhase.clear()
                sharing = false
                updateWaitingStatus()
            }
            "room-closed" -> fatal("ROOM_CLOSED", strings.getString(R.string.session_room_closed))
            "viewer-grant-revoked" -> if (params.viewerGrant != null) fatal("GRANT_REVOKED", strings.getString(R.string.session_grant_revoked))
            "host-status" -> {
                hostOnline = m.optBoolean("online", true)
                hostPaused = m.optBoolean("paused", false)
                if (active?.firstFrame != true || hostPaused || !hostOnline) updateWaitingStatus()
            }
            else -> Unit // quality-settings, route-policy, presence, interazioni... ignorati
        }
    }

    private fun onAuthenticated(m: JSONObject) {
        authenticated = true
        authTimeout?.cancel(false)
        reconnectAttempt = 0
        lastError = null
        peerId = m.optString("peerId")
        fence = m.optLong("routeRevision", fence)
        seenRevisionPhase.clear()
        lastActiveRevision = fence
        iceServers = parseIce(m.optJSONObject("iceConfig"))
        hostOnline = m.optBoolean("hostOnline", true)
        hostPaused = m.optBoolean("hostPaused", false)
        sharing = !m.isNull("shareGeneration")
        send(JSONObject().put("type", "relay-capacity").put("downstreamEdges", 0))
        startChallenges()

        val cid = if (m.isNull("connectionId")) null else m.optString("connectionId").ifEmpty { null }
        val upstream = m.optJSONObject("routeAssignment")?.optJSONObject("upstream")
        val parent = if (upstream?.optString("kind") == "peer") upstream.optString("peerId").ifEmpty { null } else null
        val a = active
        when {
            cid != null && a != null && a.connectionId == cid -> {
                Log.i(tag, "re-authenticated, edge $cid still committed")
            }
            cid != null && parent != null -> {
                // Il server ha ancora un collegamento per noi ma non abbiamo più la PeerConnection: chiede al PC di ricostruirlo.
                closeAllEdges()
                expectedReplacementFrom = parent
                send(JSONObject().put("type", "restart-request").put("targetPeerId", parent)
                    .put("connectionId", cid).put("rebuild", true))
                status(strings.getString(R.string.session_rejoining_stream))
            }
            else -> {
                closeAllEdges()
                updateWaitingStatus()
            }
        }
    }

    private fun onError(m: JSONObject) {
        val code = m.optString("code")
        val message = m.optString("message")
        Log.w(tag, "server error $code: $message")
        val text = when (code) {
            "ROOM_NOT_FOUND" -> strings.getString(R.string.error_room_not_found, params.roomId)
            "ROOM_ACCESS_DENIED" -> strings.getString(R.string.error_room_access_denied)
            "INVALID_TOKEN" -> strings.getString(R.string.error_invalid_token)
            "ROOM_FULL" -> strings.getString(R.string.error_room_full)
            "AUTH_REQUIRED" -> strings.getString(R.string.error_auth_required)
            "SERVER_ERROR" -> strings.getString(R.string.error_server)
            "INVALID_MESSAGE" -> strings.getString(R.string.error_invalid_message)
            else -> null
        }
        if (text != null) {
            lastError = code to text
            if (!authenticated || code == "INVALID_MESSAGE") fatal(code, text)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Percorsi media (route controller di Piik)
    // ---------------------------------------------------------------------------------------

    private fun onRouteUpdate(m: JSONObject) {
        val rev = m.optLong("revision", -1)
        val phase = m.optString("phase")
        if (rev < 0 || rev < fence) return
        if (!seenRevisionPhase.add("$rev/$phase")) return
        fence = maxOf(fence, rev)
        val upstream = m.optJSONObject("assignment")?.optJSONObject("upstream")
        val kind = upstream?.optString("kind")
        val parent = upstream?.optString("peerId")?.ifEmpty { null }
        when (phase) {
            "prepare" -> {
                val cand = m.optJSONObject("candidate") ?: return
                val child = cand.optString("childPeerId")
                val cid = cand.optString("connectionId")
                if (cid.isEmpty()) return
                val probe = cand.optBoolean("qualityProbe", false)
                val transport = cand.optString("transport")
                if (child != peerId || probe || transport != "direct" || kind != "peer" || parent == null) {
                    // Siamo una foglia: niente relay, niente SFU, niente prove di qualità.
                    sendRouteFailed(rev, "prepare", cid)
                    return
                }
                sharing = true
                pending?.let { closeEdge(it) }
                val e = Edge(rev, parent, cid, isReplacement = false)
                pending = e
                exhaustedTask?.cancel(false)
                if (active?.firstFrame != true) status(strings.getString(R.string.session_linking))
                replayQueuedSignals()
            }
            "active" -> {
                lastActiveRevision = rev
                val p = pending
                if (p != null && rev == p.revision && kind == "peer" && parent == p.parentPeerId) {
                    val old = active
                    active = p
                    pending = null
                    if (old != null && old !== p) closeEdge(old)
                    Log.i(tag, "route committed: $p")
                    everPlayed = true
                } else {
                    if (p != null && rev > p.revision) {
                        closeEdge(p)
                        pending = null
                    }
                    val a = active
                    if (a != null && (kind != "peer" || parent != a.parentPeerId)) {
                        closeEdge(a)
                        active = null
                        updateWaitingStatus()
                    }
                }
            }
        }
    }

    private fun onRouteStatus(m: JSONObject) {
        when (m.optString("state")) {
            "failed" -> {
                status(strings.getString(R.string.session_route_failed))
                exhaustedTask?.cancel(false)
                exhaustedTask = exec.schedule({
                    if (active?.connected != true && pending == null) {
                        sig?.close()
                        onSignalClosed(1006, "route exhausted, new session")
                    }
                }, 8, TimeUnit.SECONDS)
            }
            "waiting" -> if (active?.firstFrame != true) status(strings.getString(R.string.session_route_waiting))
        }
    }

    private fun updateWaitingStatus() {
        status(
            when {
                !hostOnline -> strings.getString(R.string.session_host_offline, params.roomId)
                hostPaused -> strings.getString(R.string.session_host_paused)
                !sharing -> strings.getString(R.string.session_waiting_share)
                else -> strings.getString(R.string.session_waiting_video)
            }
        )
    }

    private fun sendRouteFailed(rev: Long, phase: String, cid: String?) {
        send(JSONObject().put("type", "route-failed").put("revision", rev).put("phase", phase)
            .put("connectionId", cid ?: JSONObject.NULL))
    }

    private fun replayQueuedSignals() {
        if (queuedSignals.isEmpty()) return
        val copy = ArrayList(queuedSignals)
        queuedSignals.clear()
        for ((from, payload) in copy) onSignal(from, payload)
    }

    // ---------------------------------------------------------------------------------------
    // Segnali WebRTC (SDP / ICE) dal genitore
    // ---------------------------------------------------------------------------------------

    private fun onSignal(from: String, payload: JSONObject?) {
        if (payload == null || from.isEmpty()) return
        val kind = payload.optString("kind")
        val cid = payload.optString("connectionId")
        val p = pending
        val a = active
        val isOffer = kind == "description" && payload.optJSONObject("description")?.optString("type") == "offer"
        val edge: Edge? = when {
            p != null && p.connectionId == cid && p.parentPeerId == from -> p
            a != null && a.connectionId == cid && a.parentPeerId == from -> a
            isOffer && (from == a?.parentPeerId || from == expectedReplacementFrom) -> {
                // Sostituzione del collegamento attivo (il mittente nativo non fa ICE restart):
                // nuova PeerConnection con nuovo connectionId, nessuna nuova prova richiesta.
                val rev = a?.revision ?: lastActiveRevision
                a?.let { closeEdge(it) }
                val e = Edge(rev, from, cid, isReplacement = true)
                active = e
                expectedReplacementFrom = null
                Log.i(tag, "replacement edge $e")
                e
            }
            else -> null
        }
        if (edge == null) {
            if (queuedSignals.size < 64) queuedSignals.add(from to payload)
            return
        }
        when (kind) {
            "description" -> {
                val d = payload.optJSONObject("description") ?: return
                if (d.optString("type") == "offer") handleOffer(edge, d.optString("sdp"))
            }
            "candidate" -> {
                if (payload.isNull("candidate")) return
                val c = payload.optJSONObject("candidate") ?: return
                val ic = IceCandidate(
                    if (c.isNull("sdpMid")) "" else c.optString("sdpMid"),
                    if (c.isNull("sdpMLineIndex")) 0 else c.optInt("sdpMLineIndex", 0),
                    c.optString("candidate"),
                )
                if (edge.remoteSet) edge.pc?.addIceCandidate(ic) else edge.pendingCandidates.add(ic)
            }
            "punch" -> {
                if (payload.optJSONObject("punch")?.optString("phase") == "prepare") {
                    sendSignal(from, JSONObject().put("kind", "punch").put("connectionId", cid)
                        .put("punch", JSONObject().put("phase", "ready")))
                }
            }
        }
    }

    private fun sendSignal(target: String, payload: JSONObject) {
        send(JSONObject().put("type", "signal").put("targetPeerId", target).put("payload", payload))
    }

    private fun handleOffer(edge: Edge, sdp: String) {
        if (edge.closed) return
        if (edge.negotiating) {
            edge.queuedOffers.addLast(sdp)
            return
        }
        edge.negotiating = true
        val pc = edge.pc ?: createPeerConnection(edge)
        if (pc == null) {
            failEdge(edge, "createPeerConnection")
            return
        }
        val offer = SessionDescription(SessionDescription.Type.OFFER, SdpMunger.remoteOffer(sdp, params.avSync))
        pc.setRemoteDescription(sdpObserver(edge, "setRemoteDescription") {
            edge.remoteSet = true
            for (c in edge.pendingCandidates) pc.addIceCandidate(c)
            edge.pendingCandidates.clear()
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        post { failEdge(edge, "createAnswer: null") }
                        return
                    }
                    post {
                        if (edge.closed) return@post
                        val munged = SdpMunger.localAnswer(desc.description)
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, munged)
                        pc.setLocalDescription(sdpObserver(edge, "setLocalDescription") {
                            sendSignal(edge.parentPeerId, JSONObject()
                                .put("kind", "description")
                                .put("connectionId", edge.connectionId)
                                .put("description", JSONObject().put("type", "answer").put("sdp", munged)))
                            edge.negotiating = false
                            startProofWatch(edge)
                            edge.queuedOffers.removeFirstOrNull()?.let { handleOffer(edge, it) }
                        }, answer)
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) = post { failEdge(edge, "createAnswer: $error") }.let { }
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }, offer)
    }

    private fun sdpObserver(edge: Edge, what: String, onSuccess: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {
            post { if (!edge.closed) onSuccess() }
        }

        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {
            post { failEdge(edge, "$what: $error") }
        }
    }

    private fun createPeerConnection(edge: Edge): PeerConnection? {
        val f = factory ?: return null
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            keyType = PeerConnection.KeyType.ECDSA
            // Audio: jitter buffer piccolo e accelerazione rapida per non accumulare ritardo
            audioJitterBufferMaxPackets = 20
            audioJitterBufferFastAccelerate = true
            enableDscp = true
            enableCpuOveruseDetection = false
        }
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) post {
                    if (!edge.closed) sendSignal(edge.parentPeerId, JSONObject().put("kind", "candidate")
                        .put("connectionId", edge.connectionId).put("candidate", JSONObject.NULL))
                }
            }

            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                post {
                    if (edge.closed) return@post
                    val cj = JSONObject().put("candidate", c.sdp)
                    if (c.sdpMid != null) cj.put("sdpMid", c.sdpMid)
                    cj.put("sdpMLineIndex", c.sdpMLineIndex)
                    sendSignal(edge.parentPeerId, JSONObject().put("kind", "candidate")
                        .put("connectionId", edge.connectionId).put("candidate", cj))
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (state == null) return
                post { onConnectionState(edge, state) }
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            // Callback su thread nativi di WebRTC: mai lasciar uscire eccezioni (abort dell'app).
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                try {
                    val t = receiver?.track()
                    if (t is AudioTrack) t.setEnabled(params.audioEnabled)
                } catch (e: Throwable) {
                    Log.w(tag, "onAddTrack: $e")
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                try {
                    val t = transceiver?.receiver?.track()
                    if (t is AudioTrack) t.setEnabled(params.audioEnabled)
                } catch (e: Throwable) {
                    Log.w(tag, "onTrack: $e")
                }
            }
        }) ?: return null
        edge.pc = pc
        Log.i(tag, "peer connection created for $edge")
        return pc
    }

    private fun onConnectionState(edge: Edge, state: PeerConnection.PeerConnectionState) {
        if (edge.closed) return
        Log.i(tag, "pc $state $edge")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                edge.connected = true
                edge.disconnectTask?.cancel(false)
                edge.recoveryTask?.cancel(false)
                edge.recoveryStep = 0
                if (!edge.transportConnectedSent && !edge.isReplacement) {
                    edge.transportConnectedSent = true
                    send(JSONObject().put("type", "route-transport-connected")
                        .put("revision", edge.revision).put("connectionId", edge.connectionId))
                }
                startProofWatch(edge)
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                edge.connected = false
                edge.disconnectTask?.cancel(false)
                edge.disconnectTask = exec.schedule({
                    if (!edge.closed && !edge.connected) failEdge(edge, "disconnected > 3 s")
                }, 3, TimeUnit.SECONDS)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                edge.connected = false
                failEdge(edge, "ice failed")
            }
            else -> Unit
        }
    }

    /** Attende il primo fotogramma decodificato (framesDecoded > 0), poi invia route-ready. */
    private fun startProofWatch(edge: Edge) {
        if (edge.firstFrame || edge.closed || edge.proofTask != null) return
        edge.proofTask = exec.scheduleWithFixedDelay({
            val pc = edge.pc
            if (edge.closed || pc == null) return@scheduleWithFixedDelay
            pc.getStats { report ->
                var decoded = 0.0
                for (s in report.statsMap.values) {
                    if (s.type == "inbound-rtp" && s.members["kind"] == "video") {
                        decoded = (s.members["framesDecoded"] as? Number)?.toDouble() ?: 0.0
                    }
                }
                if (decoded > 0) post { onFirstFrame(edge) }
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    private fun onFirstFrame(edge: Edge) {
        if (edge.firstFrame || edge.closed) return
        edge.firstFrame = true
        edge.proofTask?.cancel(false)
        edge.proofTask = null
        if (!edge.readySent && edge === pending) {
            edge.readySent = true
            send(JSONObject().put("type", "route-ready").put("revision", edge.revision).put("phase", "prepare"))
            Log.i(tag, "route-ready sent for $edge")
        }
        listener.onFirstFrame()
    }

    /** Collegamento in errore: candidato -> route-failed; attivo -> scala di recupero di Piik. */
    private fun failEdge(edge: Edge, why: String) {
        if (edge.closed) return
        Log.w(tag, "edge failure ($why): $edge")
        if (edge === pending) {
            sendRouteFailed(edge.revision, "prepare", edge.connectionId)
            closeEdge(edge)
            pending = null
            if (active == null) updateWaitingStatus()
            return
        }
        if (edge !== active) {
            closeEdge(edge)
            return
        }
        if (edge.recoveryStep > 0) return
        if (!hostOnline) return
        status(strings.getString(R.string.session_recovering))
        edge.recoveryStep = 1
        send(JSONObject().put("type", "restart-request").put("targetPeerId", edge.parentPeerId)
            .put("connectionId", edge.connectionId).put("rebuild", false))
        edge.recoveryTask = exec.schedule({
            if (edge.closed || edge.connected) return@schedule
            edge.recoveryStep = 2
            send(JSONObject().put("type", "restart-request").put("targetPeerId", edge.parentPeerId)
                .put("connectionId", edge.connectionId).put("rebuild", true))
            edge.recoveryTask = exec.schedule({
                if (edge.closed || edge.connected) return@schedule
                sendRouteFailed(maxOf(lastActiveRevision, edge.revision), "active", edge.connectionId)
                closeEdge(edge)
                if (active === edge) active = null
                updateWaitingStatus()
            }, 3, TimeUnit.SECONDS)
        }, 3, TimeUnit.SECONDS)
    }

    private fun closeEdge(edge: Edge) {
        if (edge.closed) return
        edge.closed = true
        edge.disconnectTask?.cancel(false)
        edge.recoveryTask?.cancel(false)
        edge.proofTask?.cancel(false)
        val pc = edge.pc
        edge.pc = null
        try {
            pc?.close()
            pc?.dispose()
        } catch (e: Exception) {
            Log.w(tag, "close pc: $e")
        }
        Log.i(tag, "edge closed $edge")
    }

    private fun closeAllEdges() {
        pending?.let { closeEdge(it) }
        pending = null
        active?.let { closeEdge(it) }
        active = null
        queuedSignals.clear()
    }

    private fun parseIce(cfg: JSONObject?): List<PeerConnection.IceServer> {
        val arr: JSONArray = cfg?.optJSONArray("iceServers") ?: return emptyList()
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val urls = ArrayList<String>()
            when (val u = s.opt("urls")) {
                is String -> urls += u
                is JSONArray -> for (j in 0 until u.length()) u.optString(j).takeIf { it.isNotEmpty() }?.let { urls += it }
            }
            if (urls.isNotEmpty()) out += PeerConnection.IceServer.builder(urls).createIceServer()
        }
        return out
    }

    companion object {
        const val PROTOCOL = "piik-v24"
    }
}
