package com.helix.provider.api.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The OkHttp-backed [WireClient] (HXA-025). Implementation-scoped behind the seam:
 * none of the OkHttp types appear in the public provider API.
 *
 * Semantics:
 * - the exchange is opened with a blocking OkHttp call on [Dispatchers.IO] (the
 *   suspending [WireClient.open] is a coroutine-friendly wrapper; the read
 *   timeout bounds a stuck peer);
 * - [WireBody.forEachChunk] performs its blocking reads on the CALLING thread
 *   (the caller must be on a non-UI dispatcher — [com.helix.provider.api.WireModelProvider]
 *   guarantees this with `flowOn(Dispatchers.IO)`); no inner dispatcher hop: the
 *   chunk callback is where the provider emits into the event flow, and a
 *   dispatcher switch around it breaks the flow's SafeCollector context check;
 * - the [WireResponse] owns the connection: the caller MUST [WireBody.close] the
 *   body when done (a `finally` in the provider's flow) — for non-2xx responses
 *   the body is drained and closed before the status is consumed;
 * - [WireBody.bytes] and [WireBody.forEachChunk] are bounded by [maxBodyBytes]: a
 *   longer body fails with [IOException] instead of growing unbounded (provider
 *   streams are additionally bounded per line/event by the SSE readers);
 * - no request/response logging at any level: the body may contain conversation
 *   content, and headers may carry credentials (doc 02 section 6.2: zero secrets
 *   in logs).
 *
 * Cancellation note: a collector cancelling a model event flow stops consuming
 * chunks; the underlying read continues until the next chunk boundary where the
 * consumer stops, after which the body is closed. The read timeout remains the
 * hard bound.
 */
public class OkHttpWireClient(
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MS,
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build(),
) : WireClient {
    override suspend fun open(request: WireRequest): WireResponse =
        withContext(Dispatchers.IO) {
            val okRequest =
                Request0Builder.build(request, JSON_MEDIA_TYPE)
            val response = client.newCall(okRequest).execute()
            val headers = HashMap<String, List<String>>()
            for (name in response.headers.names()) {
                headers[name] = response.headers.values(name)
            }
            WireResponse(response.code, headers, OkHttpBody(response, maxBodyBytes))
        }

    /** The body of one OkHttp response; owns the connection until [close]. */
    private class OkHttpBody(
        private val response: okhttp3.Response,
        private val maxBodyBytes: Long,
    ) : WireBody {
        private var consumed = 0L
        private var closed = false

        override suspend fun bytes(): ByteArray {
            checkOpen()
            val out = ArrayList<ByteArray>()
            var total = 0
            forEachChunk { chunk ->
                out += chunk
                total += chunk.size
                true
            }
            close()
            val result = ByteArray(total)
            var offset = 0
            out.forEach { chunk ->
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        }

        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
            checkOpen()
            // BLOCKING reads on the CALLING thread (no withContext): the caller is
            // contractually on a non-UI dispatcher (WireModelProvider collects its
            // stream flowOn(Dispatchers.IO)). Wrapping this in withContext(Dispatchers.IO)
            // would run the callback — and the flow emission out of it — in a child
            // coroutine context, which the SafeCollector cross-context emission check
            // rejects (HXA-027 device smoke caught exactly that).
            val source = response.body.source()
            val buffer = okio.Buffer()
            var read = source.read(buffer, CHUNK_SIZE)
            while (read >= 0) {
                consumed += read
                if (consumed > maxBodyBytes) {
                    close()
                    throw IOException("response body exceeds $maxBodyBytes bytes")
                }
                if (!onChunk(buffer.readByteArray())) return
                read = source.read(buffer, CHUNK_SIZE)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            response.close()
        }

        private fun checkOpen() {
            if (closed) throw IOException("response body is already closed")
        }
    }

    public companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MS = 120_000L
        const val DEFAULT_MAX_BODY_BYTES = 8L * 1024 * 1024
        private const val CHUNK_SIZE = 16L * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /** File-private OkHttp request construction (keeps the OkHttp types out of the public surface). */
    private object Request0Builder {
        fun build(
            request: WireRequest,
            mediaType: okhttp3.MediaType,
        ): okhttp3.Request =
            okhttp3.Request
                .Builder()
                .url(request.url)
                .method(
                    request.method,
                    request.body?.let { it.toRequestBody(mediaType) },
                ).apply { request.headers.forEach { (name, value) -> header(name, value) } }
                .build()
    }
}
