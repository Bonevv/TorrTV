package app.torrtv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    class HttpError(val code: Int, message: String) : IOException(message)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Прави OkHttp заявка отменяема от корутини (за да работят таймаутите). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        var hasUa = false
        for ((k, v) in headers) {
            if (k.equals("User-Agent", true)) hasUa = true
            b.header(k, v)
        }
        if (!hasUa) b.header("User-Agent", BROWSER_UA)
        return b
    }

    private fun fail(r: Response): Nothing {
        val snippet = try {
            r.body?.string()?.take(140)?.replace("\n", " ") ?: ""
        } catch (_: Exception) {
            ""
        }
        throw HttpError(r.code, "HTTP ${r.code}${if (snippet.isNotBlank()) ": $snippet" else ""}")
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers).build()).await().use { r ->
                if (!r.isSuccessful) fail(r)
                r.body?.string() ?: ""
            }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers).build()).await().use { r ->
                if (!r.isSuccessful) fail(r)
                r.body?.bytes() ?: ByteArray(0)
            }
        }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val body = json.toRequestBody("application/json".toMediaType())
            client.newCall(request(url, headers).post(body).build()).await().use { r ->
                if (!r.isSuccessful) fail(r)
                r.body?.string() ?: ""
            }
        }
}
