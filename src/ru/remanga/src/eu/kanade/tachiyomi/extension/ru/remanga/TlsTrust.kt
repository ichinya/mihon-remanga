package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.extension.R
import keiyoushi.utils.applicationContext
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// SHA-256 of the bundled GlobalSign Root R46 (copy from AOSP system/ca-certificates 1b0f7e5c.0,
// byte-identical to the Mozilla CA bundle). Devices whose root store predates this root reject
// remanga's 2025-era GlobalSign chain with "Trust anchor for certification path not found".
private const val R46_SHA256 = "4fa3126d8d3a11d1c4855a4f807cbad6cf919d3a5a88b03bea2c6372d93c40c9"

/**
 * Extends this client's trust anchors with GlobalSign Root R46 while keeping full certificate
 * validation: the default system trust manager is tried first, the bundled-root manager only
 * as a fallback path. Hostname verification and every other TLS check stay untouched.
 */
internal fun OkHttpClient.Builder.addGlobalSignR46TrustAnchor(): OkHttpClient.Builder {
    val anchor = runCatching {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(
            applicationContext.resources.openRawResource(R.raw.globalsign_root_r46),
        ) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        check(digest == R46_SHA256) { "unexpected fingerprint: $digest" }
        cert
    }.getOrNull() ?: return this

    val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
    val fallbackTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply {
            init(
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("globalsign_root_r46", anchor)
                },
            )
        }
    val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    val fallbackTm = fallbackTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    val compositeTm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTm.checkClientTrusted(chain, authType)
            } catch (e: CertificateException) {
                fallbackTm.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTm.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                fallbackTm.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers + fallbackTm.acceptedIssuers
    }

    // The SSLContext must be initialized WITH the composite trust manager: the JSSE socket
    // performs the actual trust validation during startHandshake(), so a context created with
    // null trust managers would validate against the system store only and reject the chain
    // before OkHttp ever consults compositeTm.
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(compositeTm), null) }
    return sslSocketFactory(sslContext.socketFactory, compositeTm)
}
