package com.helix.extensions.mcp.oauth

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal suspend fun Call.oauthResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }

internal fun ResponseBody.oauthText(limit: Int): String {
    require(contentLength() <= limit) { "OAuth response too large" }
    return byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count == -1) break
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "OAuth response too large" }
        }
        output.toString(Charsets.UTF_8.name())
    }
}
