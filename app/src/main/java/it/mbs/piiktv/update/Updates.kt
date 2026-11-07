package it.mbs.piiktv.update

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Aggiornamenti di PIIK TV dalle release pubbliche di GitHub (Kaitonume/Piik-TV).
 *
 * Regole delle note di release (le stesse di VibePiik):
 * - `<!-- importance: important -->` nelle note rende l'aggiornamento importante (riga invisibile
 *   su GitHub); senza, è facoltativo;
 * - il changelog mostrato è la riga riassuntiva nella lingua dell'app (`**EN**`, `**IT**`,
 *   `**中文**`, dopo la linea `---`) seguita dal testo principale inglese, senza la sezione
 *   "Install" e senza blocchi di codice.
 */
object Updates {
    const val REPO = "Kaitonume/Piik-TV"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** Una release più nuova di quella installata. */
    data class Release(
        val version: String,      // "1.6.0" (senza la v)
        val tag: String,
        val important: Boolean,
        val body: String,         // note complete (Markdown)
        val apkName: String,
        val apkUrl: String,
        val sha256: String?,      // dal campo "digest" dell'asset, se c'è
        val size: Long,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Esito di un controllo: release più nuova, già aggiornata, oppure GitHub non raggiungibile. */
    sealed class Result {
        data class Found(val release: Release) : Result()
        object UpToDate : Result()
        object Failed : Result()
    }

    /**
     * Legge l'ultima release. [feed] sostituisce l'indirizzo delle API (solo per le prove via
     * adb). Restituisce null se non c'è niente di più nuovo di [installed] o se la rete non risponde.
     */
    fun check(installed: String, feed: String? = null): Release? =
        (checkResult(installed, feed) as? Result.Found)?.release

    /** Come [check], ma distingue «già aggiornata» da «controllo non riuscito» (controllo manuale). */
    fun checkResult(installed: String, feed: String? = null): Result = try {
        val request = Request.Builder().url(feed ?: API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PIIK-TV")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Result.Failed
            val json = JSONObject(response.body?.string() ?: return Result.Failed)
            parse(json, installed)?.let { Result.Found(it) } ?: Result.UpToDate
        }
    } catch (e: Exception) {
        Result.Failed
    }

    fun parse(json: JSONObject, installed: String): Release? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val version = Regex("^v(\\d+\\.\\d+\\.\\d+)$").find(tag)?.groupValues?.get(1) ?: return null
        if (compare(version, installed) <= 0) return null
        val body = json.optString("body")
        val assets = json.optJSONArray("assets") ?: return null
        val names = Build.SUPPORTED_ABIS.map { "PiikTV-$version-$it.apk" } + "PiikTV-$version-universal.apk"
        for (name in names) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") != name) continue
                val url = asset.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue
                val digest = asset.optString("digest").removePrefix("sha256:").lowercase(Locale.ROOT)
                    .takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
                return Release(version, tag, isImportant(body), body, name, url, digest, asset.optLong("size"))
            }
        }
        return null
    }

    fun isImportant(body: String) =
        Regex("<!--\\s*importance\\s*:\\s*important\\s*-->", RegexOption.IGNORE_CASE).containsMatchIn(body)

    /** Confronta due versioni "1.2.3": negativo se a < b. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Il changelog da mostrare, in testo semplice: riassunto nella lingua [lang] + testo inglese. */
    fun changelog(body: String, lang: String): String {
        var text = body.replace("\r\n", "\n")
            .replace(Regex("```[\\s\\S]*?```"), "")          // blocchi di codice (impronte SHA-256)
            .replace(Regex("<!--[\\s\\S]*?-->"), "")          // commenti (importanza)
        val split = text.split(Regex("\\n-{3,}\\s*\\n"), limit = 2)
        val main = split[0]
        val tail = split.getOrElse(1) { "" }
        val marker = when (lang) { "it" -> "**IT**"; "zh" -> "**中文**"; else -> "**EN**" }
        val summary = tail.lines().firstOrNull { it.trim().startsWith(marker) }
            ?.trim()?.removePrefix(marker)?.trim()?.trimStart('—', '-', ':', ' ')
        // Senza la sezione d'installazione (fino al titolo successivo).
        text = main.replace(Regex("(?ms)^###\\s*(Install|Installazione|安装)\\b.*?(?=^###|\\z)"), "")
        val plain = text.lines()
            .filterNot { it.trim().startsWith("|") }
            .joinToString("\n") { line ->
                strip(line).let { l -> if (l.startsWith("- ")) "• " + l.substring(2) else l }
            }
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        return listOfNotNull(summary?.let(::strip)?.takeIf { it.isNotBlank() }, plain.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
    }

    private fun strip(line: String) = line
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace("**", "").replace("`", "")

    /** Impronta SHA-256 di un file, in esadecimale. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Scarica [release] in [dest], chiamando [progress] con 0..100. Verifica l'impronta se nota. */
    fun download(release: Release, dest: File, progress: (Int) -> Unit) {
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "PIIK-TV").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        }
        val expected = release.sha256
        if (expected != null && sha256(dest) != expected) {
            dest.delete()
            throw SecurityException("checksum")
        }
    }
}
