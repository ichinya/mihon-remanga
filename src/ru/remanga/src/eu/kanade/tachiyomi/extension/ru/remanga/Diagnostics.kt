package eu.kanade.tachiyomi.extension.ru.remanga

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Structured diagnostics log: JSONL files, one per day (`logs/2026-11-03.jsonl`,
 * one entry object per line). Entry fields: id, ts, level (INFO|ERROR), source
 * (remanga|exmanga|…), manga, chapter, msg.
 * Export converts entries to readable txt; upload ships raw day files to the log server,
 * which deduplicates entries by id — files are deleted locally after a successful upload.
 */
internal object Diagnostics {

    private const val LOG_SERVER = "https://logging.tw1.ru/api.php"
    private const val UPLOAD_MAX_FILES = 8
    private const val UPLOAD_MAX_FILE_BYTES = 512 * 1024
    private const val MAX_MSG_LEN = 4000
    private const val EXPORT_MAX_CHARS = 300_000
    private const val UPLOAD_THROTTLE_MS = 5 * 60_000L
    private const val KEEP_DAYS = 7L
    private const val TOAST_INTERVAL_MS = 60_000L

    @Volatile
    var autoUploadEnabled = false

    /** Source of the most recent activity, used as fallback for entries without an explicit one. */
    @Volatile
    var ctxSource: String? = null

    @Volatile
    var ctxManga: String? = null

    @Volatile
    var ctxChapter: String? = null

    private val lock = Any()
    private var lastToastAt = 0L
    private var lastUploadAt = 0L

    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val json = Json

    fun logsDir(): File = File(
        applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
        "logs",
    ).apply { mkdirs() }

    /** Legacy plain-text log from older builds — removed once the new JSONL flow ships. */
    fun deviceId(): String {
        val prefs = applicationContext.getSharedPreferences("remanga_diagnostics", 0)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"

    /** The host app's PackageInfo is Mihon's, not ours; read the version baked at build time. */
    fun selfVersion(): String = "remanga-v" + runCatching {
        eu.kanade.tachiyomi.extension.BuildConfig.EXT_VERSION
    }.getOrDefault("?")

    // ============================== запись ==============================

    fun error(msg: String, source: String? = null, manga: String? = null, chapter: String? = null) {
        write("ERROR", msg, source, manga, chapter)
        synchronized(lock) {
            saveLastSummary(msg, source, manga, chapter)
            showToast()
            if (autoUploadEnabled) uploadAsync()
        }
    }

    private fun write(level: String, msg: String, source: String?, manga: String?, chapter: String?) {
        val entry = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
            put("level", level)
            put("source", (source ?: ctxSource ?: "").take(20))
            put("manga", (manga ?: ctxManga ?: "").take(150))
            put("chapter", (chapter ?: ctxChapter ?: "").take(150))
            put("msg", msg.take(MAX_MSG_LEN))
        }
        synchronized(lock) {
            runCatching { appendEntry(entry) }
        }
    }

    private fun appendEntry(entry: JsonObject) {
        val day = LocalDateTime.now().format(dayFormat)
        val file = File(logsDir(), "$day.jsonl")
        if (file.exists() && file.length() > UPLOAD_MAX_FILE_BYTES) return // day file over limit
        file.appendText(entry.toString() + "\n")
        cleanupOldDays()
    }

    private fun cleanupOldDays() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        dayFiles()?.forEach { f ->
            val day = runCatching {
                LocalDateTime.parse(f.nameWithoutExtension, dayFormat)
            }.getOrNull()
            if (day != null && day.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() < cutoff) {
                f.delete()
            }
        }
    }

    private fun dayFiles(): Array<File>? = logsDir().listFiles { f -> f.name.endsWith(".jsonl") }

    private fun readAllEntries(): List<JsonObject> {
        val files = dayFiles() ?: return emptyList()
        val entries = mutableListOf<JsonObject>()
        for (f in files.sortedBy { it.name }) {
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                runCatching {
                    entries += json.parseToJsonElement(line).jsonObject
                }
            }
        }
        return entries.sortedBy { it["ts"]?.jsonPrimitive?.longOrNull ?: 0L }
    }

    private fun saveLastSummary(msg: String, source: String?, manga: String?, chapter: String?) {
        runCatching {
            val where = buildString {
                if (!source.isNullOrBlank()) append("[$source] ")
                if (!manga.isNullOrBlank()) append(manga)
                if (!chapter.isNullOrBlank()) append(" / ").append(chapter)
                if (isNotEmpty()) append(": ")
            }
            applicationContext.getSharedPreferences("remanga_diagnostics", 0)
                .edit()
                .putString("last_error_summary", "$where$msg".take(300))
                .apply()
        }
    }

    // ============================== просмотр/экспорт ==============================

    fun lastSummary(): String? = runCatching {
        applicationContext.getSharedPreferences("remanga_diagnostics", 0)
            .getString("last_error_summary", null)
    }.getOrNull()

    /** JSONL entries converted to a readable txt block for the share button. */
    fun exportTxt(): String {
        val entries = readAllEntries()
        if (entries.isEmpty()) return "Лог пуст — записей нет."
        val text = entries.joinToString("\n") { e ->
            val ts = e["ts"]?.jsonPrimitive?.longOrNull ?: 0L
            val timeStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.systemDefault()).format(tsFormat)
            val level = e["level"]?.jsonPrimitive?.content ?: "?"
            val source = e["source"]?.jsonPrimitive?.content.orEmpty()
            val manga = e["manga"]?.jsonPrimitive?.content.orEmpty()
            val chapter = e["chapter"]?.jsonPrimitive?.content.orEmpty()
            val msg = e["msg"]?.jsonPrimitive?.content.orEmpty()
            buildString {
                append(timeStr).append(' ').append(level)
                if (source.isNotEmpty()) append(" [").append(source).append(']')
                if (manga.isNotEmpty()) {
                    append(' ').append(manga)
                    if (chapter.isNotEmpty()) append(" / ").append(chapter)
                }
                if (msg.isNotEmpty()) append(": ").append(msg)
            }
        }
        return selfVersion() + "\n" + text.takeLast(EXPORT_MAX_CHARS)
    }

    fun clear() {
        runCatching {
            dayFiles()?.forEach { it.delete() }
            applicationContext.getSharedPreferences("remanga_diagnostics", 0)
                .edit()
                .remove("last_error_summary")
                .apply()
        }
    }

    // ============================== отправка ==============================

    /** Fire-and-forget upload, throttled to once per 5 minutes for the automatic mode. */
    fun uploadAsync(done: ((Boolean, String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastUploadAt < UPLOAD_THROTTLE_MS && done == null) return
            lastUploadAt = now
        }
        CoroutineScope(Dispatchers.IO).launch {
            val (ok, msg) = uploadNow()
            done?.invoke(ok, msg)
        }
    }

    /** Ships raw day files; the server deduplicates entries by id, so re-sends are safe. */
    fun uploadNow(): Pair<Boolean, String> {
        val files = dayFiles()?.sortedBy { it.name }?.take(UPLOAD_MAX_FILES) ?: return true to "Логов нет"
        if (files.isEmpty()) return true to "Логов нет"
        val client = OkHttpClient()
        var sent = 0
        for (f in files) {
            val payload = runCatching { f.readText() }.getOrNull() ?: continue
            if (payload.isBlank()) {
                f.delete()
                continue
            }
            val url = LOG_SERVER.toHttpUrl().newBuilder()
                .addQueryParameter("action", "upload")
                .addQueryParameter("device", deviceId())
                .addQueryParameter("model", deviceName())
                .addQueryParameter("ver", selfVersion())
                .build()
            val request = Request.Builder()
                .url(url)
                .post(payload.take(UPLOAD_MAX_FILE_BYTES).toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val ok = runCatching {
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        f.delete()
                        true
                    } else {
                        error("upload: ${f.name} → HTTP ${resp.code}")
                        false
                    }
                }
            }.getOrDefault(false)
            if (!ok) return false to "Не удалось отправить ${f.name}"
            sent++
        }
        return true to "Отправлено файлов: $sent"
    }

    // ============================== утилиты ==============================

    /** Detailed network failure: DNS resolution, peer certs on TLS errors, exception chain. */
    fun failure(request: Request, error: Throwable? = null, note: String? = null) {
        val source = when {
            request.url.host.contains("exmanga") -> "exmanga"
            request.url.host.contains("remanga") -> "remanga"
            else -> ctxSource
        }
        val msg = buildString {
            append(request.method).append(' ').append(request.url)
            runCatching {
                InetAddress.getAllByName(request.url.host).joinToString { it.hostAddress ?: "?" }
            }.getOrNull()?.let { append("\nDNS: ").append(it) }
            if (isTlsFailure(error)) {
                probePeerCertificates(request.url.host)?.let {
                    append("\nPEER CERTS: ").append(it)
                }
            }
            if (note != null) append("\n").append(note)
            var cause: Throwable? = error
            while (cause != null) {
                append("\n").append(cause.javaClass.name).append(": ").append(cause.message)
                cause = cause.cause
            }
        }
        error(msg, source)
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
                .joinToString("\n") { cert ->
                    "subject=\"${cert.subjectX500Principal.name}\"" +
                        " issuer=\"${cert.issuerX500Principal.name}\"" +
                        " notAfter=${cert.notAfter}"
                }
        } finally {
            runCatching { socket.close() }
        }
    }.getOrNull()
}

/**
 * Outermost source interceptor: records the outcome the user actually sees, after the
 * image-CDN fallback has retried. Never alters requests, responses or errors.
 */
internal object DiagnosticsInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = when {
            request.url.host.contains("exmanga") -> "exmanga"
            request.url.host.contains("remanga") -> "remanga"
            else -> Diagnostics.ctxSource
        }
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
            response.code >= 400 && !(isExHistory(request) && response.code == 401) ->
                Diagnostics.failure(request, note = "HTTP ${response.code}")
            request.url.host.contains("remanga") &&
                response.headers["Content-Type"].orEmpty().contains("text/html") ->
                Diagnostics.failure(
                    request,
                    note = "HTTP ${response.code} · получен HTML вместо API/изображения" +
                        " (DDoS-Guard: гео-блок или перехват)",
                )
        }
        return response
    }

    // A 401 from the ExManga history endpoint is expected when the reader has no account —
    // the chapter list still loads with locked chapters, so it is not worth reporting.
    private fun isExHistory(request: Request): Boolean = request.url.host.contains("exmanga") && request.url.encodedPath.contains("/manga/history")
}
