package eu.kanade.tachiyomi.extension.ru.remanga

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal class RequestTrace {
    private val started = System.nanoTime()
    private var exchangeFailure: IOException? = null
    private var bodyFailure: IOException? = null
    var call: Call? = null

    fun isCanceled(error: IOException): Boolean = error.message == "Canceled" ||
        (call?.isCanceled() == true && !(error is InterruptedIOException && error.message == "timeout"))

    fun describe(error: IOException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }
        val stage = when {
            bodyFailure != null && causes.any { it === bodyFailure } -> "чтение тела ответа"
            causes.any { it is UnknownHostException } -> "поиск адреса DNS"
            causes.any { it is SSLException } -> "обмен TLS"
            causes.any { it is ConnectException } -> "подключение"
            exchangeFailure != null && causes.any { it === exchangeFailure } -> "отправка запроса или ожидание заголовков ответа"
            else -> "до начала HTTP-обмена (ожидание или установка соединения)"
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return "Этап: $stage. Время до сбоя: $elapsed мс."
    }

    // Body reads happen after the application interceptor has returned the response.
    fun watchBody(response: Response, onFailure: (IOException) -> Unit): Response {
        val body = response.body
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount)
            } catch (e: IOException) {
                if (bodyFailure == null) {
                    bodyFailure = e
                    if (!isCanceled(e)) onFailure(e)
                }
                throw e
            }
        }
        return response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }

    companion object {
        // OkHttp enters network interceptors only after it has a connection. Match the exception
        // to the final failure so a recovered exchange cannot mislabel a later connection failure.
        val networkInterceptor = Interceptor { chain ->
            val request = chain.request()
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                request.tag(RequestTrace::class.java)?.exchangeFailure = e
                throw e
            }
        }
    }
}
