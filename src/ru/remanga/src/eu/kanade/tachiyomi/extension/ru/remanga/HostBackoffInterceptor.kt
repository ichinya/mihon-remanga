package eu.kanade.tachiyomi.extension.ru.remanga

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

internal class HostBackoffInterceptor : Interceptor {
    private val hosts = mutableMapOf<String, HostBackoff>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        val backoff = synchronized(hosts) { hosts.getOrPut(request.url.host) { HostBackoff() } }
        val generation = backoff.awaitTurn(call)
        var failed: Boolean? = null
        try {
            val response = chain.proceed(request)
            if (response.cacheResponse == null || response.networkResponse != null) {
                failed = response.code == 408 || response.code == 429 || response.code in 500..599 ||
                    (response.code == 403 && response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true))
            }
            return response
        } catch (e: IOException) {
            if (e.message != "Canceled") failed = true
            throw e
        } finally {
            backoff.complete(generation, if (call.isCanceled()) null else failed)
        }
    }

    private class HostBackoff {
        private var generation = 0L
        private var failures = 0
        private var retryAt = 0L
        private var probeInFlight = false

        fun awaitTurn(call: Call): Long {
            while (true) {
                if (call.isCanceled()) throw IOException("Canceled")
                val waitMillis = synchronized(this) {
                    if (failures == 0) return generation
                    if (probeInFlight) return@synchronized CANCEL_CHECK_MILLIS

                    val remaining = retryAt - System.nanoTime()
                    if (remaining <= 0) {
                        probeInFlight = true
                        return generation
                    }
                    TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, CANCEL_CHECK_MILLIS)
                }
                try {
                    Thread.sleep(waitMillis)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting to retry").apply { initCause(e) }
                }
            }
        }

        fun complete(attemptGeneration: Long, failed: Boolean?) = synchronized(this) {
            // Requests already in flight when the first failure occurred belong to the old generation.
            if (attemptGeneration != generation) return@synchronized
            probeInFlight = false
            when (failed) {
                true -> {
                    generation++
                    failures = (failures + 1).coerceAtMost(RETRY_DELAYS_SECONDS.size)
                    retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_DELAYS_SECONDS[failures - 1])
                }
                false -> failures = 0
                null -> Unit
            }
        }
    }

    private companion object {
        const val CANCEL_CHECK_MILLIS = 100L
        val RETRY_DELAYS_SECONDS = longArrayOf(1, 5, 10, 20, 40, 60)
    }
}
