package it.mbs.piiktv.session

/**
 * Ritocchi SDP necessari per Piik e per la latenza minima.
 */
object SdpMunger {

    /**
     * Offerta del PC, prima di setRemoteDescription.
     * Piik mette audio e video nello stesso MediaStream ("piik-native"): libwebrtc li sincronizza e su
     * Android l'audio dichiara ~150 ms di ritardo fisso, che verrebbero aggiunti anche al video.
     * Se la sincronizzazione labiale non è richiesta, l'audio viene spostato in uno stream separato.
     */
    fun remoteOffer(sdp: String, avSync: Boolean): String {
        if (avSync) return sdp
        val lines = sdp.split("\r\n", "\n")
        val out = StringBuilder(sdp.length + 64)
        var media = ""
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.startsWith("m=")) media = line.substring(2).substringBefore(' ')
            var l = line
            if (media == "audio") {
                if (l.startsWith("a=msid:")) {
                    val rest = l.removePrefix("a=msid:")
                    val stream = rest.substringBefore(' ')
                    val track = rest.substringAfter(' ', "")
                    if (stream != "-") l = "a=msid:$stream-audio" + if (track.isNotEmpty()) " $track" else ""
                } else if (l.startsWith("a=ssrc:") && l.contains(" msid:")) {
                    val head = l.substringBefore(" msid:")
                    val rest = l.substringAfter(" msid:")
                    val stream = rest.substringBefore(' ')
                    val track = rest.substringAfter(' ', "")
                    if (stream != "-") l = "$head msid:$stream-audio" + if (track.isNotEmpty()) " $track" else ""
                }
            }
            out.append(l).append("\r\n")
        }
        return out.toString()
    }

    /**
     * Risposta locale, prima di setLocalDescription e dell'invio al PC.
     * Il mittente nativo di Piik rifiuta una risposta il cui fmtp Opus non contiene
     * maxaveragebitrate=510000 (e il browser aggiunge anche stereo=1): li impostiamo uguali.
     */
    fun localAnswer(sdp: String): String {
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }.toMutableList()
        var media = ""
        var audioRejected = false
        val opusPts = HashSet<String>()
        // 1) payload type di Opus nelle sezioni audio
        for (line in lines) {
            if (line.startsWith("m=")) {
                media = line.substring(2).substringBefore(' ')
                audioRejected = media == "audio" && line.split(' ').getOrNull(1) == "0"
            }
            if (media == "audio" && !audioRejected && line.startsWith("a=rtpmap:") && line.contains(" opus/", ignoreCase = true)) {
                opusPts += line.removePrefix("a=rtpmap:").substringBefore(' ')
            }
        }
        if (opusPts.isEmpty()) return lines.joinToString("\r\n", postfix = "\r\n")
        val result = ArrayList<String>(lines.size + 2)
        val fmtpSeen = HashSet<String>()
        for (line in lines) {
            if (line.startsWith("a=fmtp:")) {
                val pt = line.removePrefix("a=fmtp:").substringBefore(' ')
                if (pt in opusPts) {
                    fmtpSeen += pt
                    val params = line.substringAfter(' ', "").split(';')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .filterNot { it.startsWith("stereo=") || it.startsWith("maxaveragebitrate=") }
                    val merged = params + listOf("stereo=1", "maxaveragebitrate=510000")
                    result += "a=fmtp:$pt " + merged.joinToString(";")
                    continue
                }
            }
            result += line
        }
        // 2) se manca la riga fmtp di Opus, la aggiunge subito dopo rtpmap
        for (pt in opusPts - fmtpSeen) {
            val idx = result.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            if (idx >= 0) result.add(idx + 1, "a=fmtp:$pt minptime=10;useinbandfec=1;stereo=1;maxaveragebitrate=510000")
        }
        return result.joinToString("\r\n", postfix = "\r\n")
    }
}
