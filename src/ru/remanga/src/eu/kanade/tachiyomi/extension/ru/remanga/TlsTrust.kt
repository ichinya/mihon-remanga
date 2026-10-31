package eu.kanade.tachiyomi.extension.ru.remanga

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// GlobalSign Root R46 (DER, base64) — copy from AOSP system/ca-certificates 1b0f7e5c.0,
// byte-identical to the Mozilla CA bundle. Embedded as a constant on purpose: extension
// resources are not reachable through the host app's Resources object, and devices whose
// root store predates this root reject remanga's 2025-era GlobalSign chain with
// "Trust anchor for certification path not found".
private const val R46_DER_BASE64 =
    "MIIFWjCCA0KgAwIBAgISEdK7udcjGJ5AXwqdLdDfJWfRMA0GCSqGSIb3DQEBDAUAMEYxCzAJBgNVBAYTAkJFMRkwFwYDVQQKExBHbG9iYWxTaWduIG52LXNhMRwwGgYDVQQDExNHbG9iYWxTaWduIFJvb3QgUjQ2MB4XDTE5MDMyMDAwMDAwMFoXDTQ2MDMyMDAwMDAwMFowRjELMAkGA1UEBhMCQkUxGTAXBgNVBAoTEEdsb2JhbFNpZ24gbnYtc2ExHDAaBgNVBAMTE0dsb2JhbFNpZ24gUm9vdCBSNDYwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCsrHQy6LNl5brtQyYdpokNRbopiLKkHWPd08EsCVeJOaFV6Wc0dwxu5FUdUiXSE2te4R2pt32JMl8Nnp8semNgQB+msLZ4j5lUlghYruQGvGIFAha/r6gjA7aUD7xubMLL1aa7DOn2wQL7Id5m3RerdELv8HQvJfTqa1VbkNud316HCkD7rRlr+/fKYIje2sGP1q7Vf9Q8g+7XFkyDRTNrJ9CG0Bwta/OrffGFqfUo0q3v84RLHIf8E6M6cqJaESvWJ3En7YEtbWaBkoe0G1h6zD8K+kZPTXhc+CtI4wSEy132tGqzZfxCnlEmIyDLPRT5ge1lFgBPGmSXZgjPjHvjK8Cd+RTyG/FWaha/LIWFzXg4mutCagI0GIMXTpRW+LaCtfOW3T3zvn8gdz57GSNrLNRyc0NXfeD412lPFzYE+cCQYDdF3uYM2HSNrpyibXRdQr4G9dlkbgIQrImwTDsHTUB+JMWKmIJ5jqSngiCNI/onccnfxkF0oE32kRbcRoxfKWMxWXEM2G/CtjJ9++ZdU6Z+Ffy7dXxd7Pj2Fxzsx2sZy/N78CsHpdlseVR2bJ0cpm4O6XkMqCNqo98bMDGfsVR7/mrLZqrcZdCinkqaByFrgY/bxFn63iLABJzjqls2k+g9vXqhnQt2sQvHnf3PmKgGwvgqo6GDoLclcqUC4wIDAQABo0IwQDAOBgNVHQ8BAf8EBAMCAYYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUA1yrc4GHqMywptWU4jaWSf8FmSwwDQYJKoZIhvcNAQEMBQADggIBAHx47PYCLLtbfpIrXTncvtgdokIzTfnvpCo7RGkerNlFo048p9gkUbJUHJNOxO97k4VgJuoJSOD1u8fpaNK7ajFxzHmuEajwmf3lH7wvqMxX63bEIaZHU1VNaL8FpO7XJqti2kM3S+LGteWygxk6x9PbTZ4IevPuzz5i+6zoYMzRx6Fcg0XERczzF2sUyQQCPtIkpnnpHs6i58FZFZ8d4kuaPp92CC1r2LpXFNqD6v6MVenQTqnMdzGxRBF6XLE+0xRFFRhiJBPSy03OXIPBNvIQtQ6IbbjhVp+J3pZmOUdkLG5NrmJ7v2B0GbhWrJKsFjLtrWhV/pi60zTe9Mlhww6G9kuEYO4Ne7UyWHmRVSyBQ7N0H3qqJZ4d16GLuc1CLgSkZoNNiTW2bKg2SnkheCLQQrzRQDGQob4Ez8pn7fXwgNNgyYMqIgXQBztSvwyeqiv5u+YfjyW6hY0XHgL+XVAEV8/+LbzvXMAaq7afJMbfc2hIkCwU9D9SGuTSyxTDYWnP4vkYxboznxSjBF25cfe1lNj2M8FawTSLfJvdkzrnE6JwYZ+vj+vYxXX4M2bUdGc6N3ec592kD3ZDZopD8p/7DEJ4Y9HiD2971KE9dJeFt0g5QdYg/NA6s/rob8SKunE3vouXsXgxT7PntgMTzlSdriVZzH81Xwj3QEUxeCp6"

// SHA-256 of the DER above, verified against both AOSP and the Mozilla CA bundle.
private const val R46_SHA256 = "4fa3126d8d3a11d1c4855a4f807cbad6cf919d3a5a88b03bea2c6372d93c40c9"

/**
 * Extends this client's trust anchors with GlobalSign Root R46 while keeping full certificate
 * validation: the default system trust manager is tried first, the bundled-root manager only
 * as a fallback path. Hostname verification and every other TLS check stay untouched.
 */
internal fun OkHttpClient.Builder.addGlobalSignR46TrustAnchor(): OkHttpClient.Builder {
    val anchor = runCatching {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.getDecoder().decode(R46_DER_BASE64)),
        ) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        check(digest == R46_SHA256) { "unexpected fingerprint: $digest" }
        cert
    }.fold(
        onSuccess = { it },
        onFailure = {
            Diagnostics.info("tls-anchor: install SKIPPED: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
            null
        },
    )

    if (anchor == null) return this

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
                Diagnostics.info("tls-anchor: system TM OK")
            } catch (e: CertificateException) {
                Diagnostics.info("tls-anchor: system TM failed -> fallback (${e.message?.take(60)})")
                fallbackTm.checkServerTrusted(chain, authType)
                Diagnostics.info("tls-anchor: fallback R46 TM OK")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers + fallbackTm.acceptedIssuers
    }

    // The SSLContext must be initialized WITH the composite trust manager: the JSSE socket
    // performs the actual trust validation during startHandshake(), so a context created with
    // null trust managers would validate against the system store only and reject the chain
    // before OkHttp ever consults compositeTm.
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(compositeTm), null) }
    Diagnostics.info("tls-anchor: installed, sslSocketFactory replaced")
    return sslSocketFactory(sslContext.socketFactory, compositeTm)
}
