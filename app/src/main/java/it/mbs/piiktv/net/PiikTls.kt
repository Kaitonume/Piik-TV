package it.mbs.piiktv.net

import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Fiducia TLS per il server Piik in LAN.
 *
 * Piik (dalla v2.5) serve la "Local room" in HTTPS con un certificato auto-firmato generato a ogni
 * avvio dell'app (CN "Piik Local", SAN = IP esatto del PC, chiave solo in memoria): non esiste una CA
 * da installare né un'impronta stabile da memorizzare. Si accettano quindi:
 *  - i certificati validi per il sistema (es. il tunnel pubblico trycloudflare.com);
 *  - oppure un certificato auto-firmato "Piik Local", firmato da sé stesso e in corso di validità.
 * La corrispondenza con l'IP richiesto è poi verificata dall'HostnameVerifier standard di OkHttp
 * (SAN IP), quindi un certificato emesso per un altro indirizzo viene rifiutato.
 */
object PiikTls {
    private const val TAG = "PiikTls"

    private val systemTrust: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            systemTrust.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("empty chain")
            try {
                systemTrust.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // non firmato da una CA: verifica se è il certificato locale di Piik
            }
            val leaf = chain[0]
            val cn = leaf.subjectX500Principal.name.split(',').map { it.trim() }
                .firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=")
            if (cn != "Piik Local" || leaf.subjectX500Principal != leaf.issuerX500Principal) {
                throw CertificateException("certificato non attendibile: $cn")
            }
            leaf.checkValidity()
            leaf.verify(leaf.publicKey) // auto-firmato davvero
            Log.i(TAG, "Piik Local certificate accepted, sha256=${fingerprint(leaf)}")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
}
