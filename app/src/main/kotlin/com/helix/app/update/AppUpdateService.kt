package com.helix.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface UpdateCheck {
    data object Current : UpdateCheck

    data object NoCompatibleRelease : UpdateCheck

    data class Available(
        val release: AppRelease,
    ) : UpdateCheck
}

internal enum class UpdateFailure { NETWORK, NOT_PUBLISHED, INVALID_MANIFEST, UNSUPPORTED_VERSION }

internal class UpdateCheckException(
    val reason: UpdateFailure,
) : IOException(reason.name)

/** User-initiated metadata GET only. No downloads, credentials, background checks or installations. */
internal class AppUpdateService(
    private val versionName: String,
    private val packageName: String,
    private val supportedAbis: List<String>,
    private val client: Call.Factory = updateClient,
    private val timeoutMillis: Long = 20_000,
) {
    suspend fun check(): UpdateCheck =
        withContext(Dispatchers.IO) {
            val installed =
                try {
                    AppVersion.parse(versionName)
                } catch (_: IllegalArgumentException) {
                    throw UpdateCheckException(UpdateFailure.UNSUPPORTED_VERSION)
                }
            val text =
                try {
                    readManifest()
                } catch (failure: UpdateCheckException) {
                    throw failure
                } catch (_: IOException) {
                    throw UpdateCheckException(UpdateFailure.NETWORK)
                }
            currentCoroutineContext().ensureActive()
            val releases =
                try {
                    UpdateManifest.parse(text)
                } catch (_: IllegalArgumentException) {
                    throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                } catch (_: NoSuchElementException) {
                    throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                } catch (_: IllegalStateException) {
                    throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                }
            val candidate = UpdateManifest.newestCompatible(releases, packageName, supportedAbis)
            when {
                candidate == null -> UpdateCheck.NoCompatibleRelease
                candidate.version > installed -> UpdateCheck.Available(candidate)
                else -> UpdateCheck.Current
            }
        }

    private suspend fun readManifest(): String =
        withTimeoutOrNull(timeoutMillis) { fetchManifest() } ?: throw UpdateCheckException(UpdateFailure.NETWORK)

    private sealed interface ManifestReply {
        data class Body(
            val text: String,
        ) : ManifestReply

        data class Redirect(
            val location: String,
        ) : ManifestReply
    }

    private suspend fun fetchManifest(): String {
        var url = URI(UPDATE_MANIFEST_URL)
        repeat(5) {
            currentCoroutineContext().ensureActive()
            requireSecureModelScope(url)
            val request =
                Request
                    .Builder()
                    .url(url.toString())
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build()
            when (val reply = execute(client.newCall(request))) {
                is ManifestReply.Body -> {
                    return reply.text
                }

                is ManifestReply.Redirect -> {
                    url =
                        try {
                            url.resolve(reply.location)
                        } catch (_: IllegalArgumentException) {
                            throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                        }
                }
            }
        }
        throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
    }

    private suspend fun execute(call: Call): ManifestReply =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            continuation.resume(response.use(::manifestReply))
                        } catch (failure: IOException) {
                            continuation.resumeWithException(failure)
                        }
                    }
                },
            )
        }

    private fun manifestReply(response: Response): ManifestReply =
        when (response.code) {
            200 -> {
                ManifestReply.Body(readBounded(response.body))
            }

            301, 302, 303, 307, 308 -> {
                ManifestReply.Redirect(
                    response.header("Location") ?: throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST),
                )
            }

            404 -> {
                throw UpdateCheckException(UpdateFailure.NOT_PUBLISHED)
            }

            else -> {
                throw UpdateCheckException(UpdateFailure.NETWORK)
            }
        }

    private fun readBounded(body: ResponseBody): String {
        if (body.contentLength() > MAX_MANIFEST_BYTES) throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count >
                    MAX_MANIFEST_BYTES
                ) {
                    throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun requireSecureModelScope(url: URI) {
        val host = url.host.orEmpty()
        val trustedHost = host == "modelscope.cn" || host.endsWith(".modelscope.cn")
        val secure = url.scheme == "https" && url.port in setOf(-1, 443)
        val plainUrl = url.rawUserInfo == null && url.rawFragment == null
        if (!secure || !trustedHost || !plainUrl) {
            throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
        }
    }

    private companion object {
        val updateClient =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
