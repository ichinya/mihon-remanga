package eu.kanade.tachiyomi.extension.ru.remanga

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import keiyoushi.utils.applicationContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Persists the last network failures so problems (TLS interception, DNS hijacks,
 * DDoS-Guard geo-blocks) can be diagnosed later from the source settings screen.
 */
internal object Diagnostics {

    private const val MAX_FILE_BYTES = 256 * 1024
    private const val TRIM_TO_BYTES = 64 * 1024
    private const val TOAST_INTERVAL_MS = 60_000L

    private val lock = Any()
    private var lastToastAt = 0L

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val logFile: File
        get() = File(
            applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
            "remanga-diagnostics.log",
        )

    fun logPath(): String = logFile.absolutePath

    /** Appends a plain diagnostic line; no toast, no prefs. */
    fun info(message: String) {
        synchronized(lock) {
            runCatching { appendToLogFile(message) }
        }
    }

    fun failure(request: Request, code: Int? = null, error: Throwable? = null, note: String? = null) {
        val dns = runCatching {
            InetAddress.getAllByName(request.url.host).joinToString { it.hostAddress ?: "?" }
        }.getOrNull()

        val peerCerts = if (isTlsFailure(error)) probePeerCertificates(request.url.host) else null

        val block = buildString {
            append(selfVersion())
            append(" · ")
            append(LocalDateTime.now().format(timestampFormat))
            append(" · ")
            append(request.method)
            append(' ')
            append(request.url)
            if (dns != null) append("\n  DNS: ").append(dns)
            if (peerCerts != null) append("\n  PEER CERTS (предъявлено устройству):\n  ").append(peerCerts)
            if (code != null) append(" · HTTP ").append(code)
            var cause: Throwable? = error
            while (cause != null) {
                append("\n  ").append(cause.javaClass.name).append(": ").append(cause.message)
                cause = cause.cause
            }
            if (note != null) append("\n  ").append(note)
        }

        synchronized(lock) {
            runCatching { appendToLogFile(block) }
            runCatching {
                applicationContext.getSharedPreferences("remanga_diagnostics", 0)
                    .edit()
                    .putString("last_error_summary", block.lineSequence().firstOrNull() ?: block)
                    .apply()
            }
            showToast()
        }
    }

    // The host app's PackageInfo is Mihon's, not ours; read the version baked at build time.
    private fun selfVersion(): String = "remanga-v" + runCatching {
        eu.kanade.tachiyomi.extension.BuildConfig.EXT_VERSION
    }.getOrDefault("?")

    private fun isTlsFailure(error: Throwable?): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SSLHandshakeException || cause is CertPathValidatorException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * One-shot diagnostic probe: completes the TLS handshake with validation disabled and records
     * the certificate chain the peer actually presents. Never used for real source traffic.
     */
    private fun probePeerCertificates(host: String): String? = runCatching {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress(host, 443), 10_000)
            socket.startHandshake()
            socket.session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .joinToString("\n  ") { cert ->
                    "subject=\"${cert.subjectX500Principal.name}\"" +
                        " issuer=\"${cert.issuerX500Principal.name}\"" +
                        " notAfter=${cert.notAfter}" +
                        " dnsNames=${cert.subjectAlternativeNames
                            ?.mapNotNull { if (it[0] == 2) it[1] else null }
                            ?.joinToString()}"
                }
        } finally {
            runCatching { socket.close() }
        }
    }.getOrNull()

    private fun appendToLogFile(block: String) {
        val file = logFile
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            file.writeText("...лог усечён...\n" + file.readText().takeLast(TRIM_TO_BYTES))
        }
        file.appendText(block + "\n\n")
    }

    private fun showToast() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    applicationContext,
                    "Remanga: ошибка сохранена в лог (настройки источника → Диагностика)",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun lastSummary(): String? = runCatching {
        applicationContext.getSharedPreferences("remanga_diagnostics", 0)
            .getString("last_error_summary", null)
    }.getOrNull()

    fun readLog(): String = runCatching {
        val file = logFile
        if (file.exists()) file.readText() else "Лог пуст — сохранённых ошибок нет."
    }.getOrDefault("Не удалось прочитать лог.")

    fun clear() {
        runCatching {
            logFile.delete()
            applicationContext.getSharedPreferences("remanga_diagnostics", 0)
                .edit()
                .remove("last_error_summary")
                .apply()
        }
    }
}

/**
 * Outermost source interceptor: records the outcome the user actually sees, after the
 * image-CDN fallback has retried. Never alters requests, responses or errors.
 */
internal object DiagnosticsInterceptor : Interceptor {
    private const val HTML_NOTE =
        "получен HTML вместо API/изображения — похоже на страницу DDoS-Guard (гео-блок или перехват)"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // Canceled loads are normal (Coil cancels cover requests when scrolling) — not failures.
            if (e.message != "Canceled") {
                Diagnostics.failure(request, error = e)
            }
            throw e
        }

        when {
            response.code >= 400 ->
                Diagnostics.failure(request, code = response.code)
            request.url.host.endsWith("remanga.org") &&
                response.headers["Content-Type"].orEmpty().contains("text/html") ->
                Diagnostics.failure(request, code = response.code, note = HTML_NOTE)
        }
        return response
    }
}
