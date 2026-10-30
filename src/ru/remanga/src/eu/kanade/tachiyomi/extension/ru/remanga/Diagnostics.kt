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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    fun failure(request: Request, code: Int? = null, error: Throwable? = null, note: String? = null) {
        val block = buildString {
            append(LocalDateTime.now().format(timestampFormat))
            append(" · ")
            append(request.method)
            append(' ')
            append(request.url)
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
            Diagnostics.failure(request, error = e)
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
