package com.helix.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

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
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
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
        withTimeoutOrNull(20_000) { fetchManifest() } ?: throw UpdateCheckException(UpdateFailure.NETWORK)

    @Suppress("ThrowsCount") // Preserve distinct HTTP, redirect and manifest failure states.
    private suspend fun fetchManifest(): String {
        var url = URI(UPDATE_MANIFEST_URL)
        repeat(5) {
            currentCoroutineContext().ensureActive()
            requireSecureModelScope(url)
            val call = connectionFactory(url)
            try {
                call.connectTimeout = 10_000
                call.readTimeout = 10_000
                call.instanceFollowRedirects = false
                call.useCaches = false
                call.setRequestProperty("Accept", "application/json")
                call.setRequestProperty("Cache-Control", "no-cache")
                when (call.responseCode) {
                    200 -> {
                        return readBounded(call)
                    }

                    301, 302, 303, 307, 308 -> {
                        val location =
                            call.getHeaderField("Location")
                                ?: throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                        url =
                            try {
                                url.resolve(location)
                            } catch (_: IllegalArgumentException) {
                                throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
                            }
                    }

                    404 -> {
                        throw UpdateCheckException(UpdateFailure.NOT_PUBLISHED)
                    }

                    else -> {
                        throw UpdateCheckException(UpdateFailure.NETWORK)
                    }
                }
            } finally {
                call.disconnect()
            }
        }
        throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
    }

    private suspend fun readBounded(call: HttpURLConnection): String {
        if (call.contentLengthLong > MAX_MANIFEST_BYTES) throw UpdateCheckException(UpdateFailure.INVALID_MANIFEST)
        return call.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
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
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
