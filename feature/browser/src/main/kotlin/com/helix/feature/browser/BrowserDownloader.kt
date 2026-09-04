package com.helix.feature.browser

import com.helix.core.workspace.AbandonedWrite
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.tools.browser.DownloadOutcome
import com.helix.tools.browser.DownloadToolStatus
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The browser download executor (HXA-063; doc 09 §3.4). Pure JVM — no WebView, no main thread —
 * so the entire production path (real [HttpURLConnection] redirect resolution + the capped
 * streaming write into the real [WorkspaceArtifactStore]) is unit-testable on the host against a
 * local server. [BrowserToolBridgeImpl.download] delegates here.
 *
 * Redirects are resolved by hand (auto-follow disabled) so every hop is re-validated against
 * [BrowserDownloadPolicy.isHttpUrl]; a hop to a non-http scheme or a loop is refused. The
 * response-header gates (MIME, sanitized name + suffix, declared size) come from
 * [BrowserDownloadPolicy.evaluate]; the bytes are then written through the store's capped
 * streaming writer, so the byte ceiling holds even when the server under-reports its length.
 * Nothing is opened or executed — the file is only ever saved.
 *
 * Every outcome is fail-closed and bounded: a non-http URL or a bad redirect hop is a REFUSED
 * outcome, an over-limit stream is a REFUSED `size-exceeded`, a socket-idle deadline is a TIMED_OUT,
 * and any network / disk / quota failure is an ERROR — in each case nothing is published (the
 * store's temp is deleted on any streaming failure).
 */
class BrowserDownloader(
    private val workspaceStore: WorkspaceArtifactStore,
    private val scopeId: String,
) {
    /**
     * Downloads [url] into the Workspace (doc 09 §3.4). Runs on the caller's thread — pure HTTP +
     * workspace I/O. [suggestedName] is a fallback hint only: the server's Content-Disposition and
     * the URL path take precedence, and every candidate is policy-sanitized before use.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod", "SwallowedException")
    fun download(
        url: String,
        suggestedName: String,
    ): DownloadOutcome {
        return try {
            if (!BrowserDownloadPolicy.isHttpUrl(url)) {
                return DownloadOutcome(DownloadToolStatus.REFUSED, "", "", "", 0L, "", "", "url")
            }
            val resolved = resolveRedirects(url)
            try {
                val rawName =
                    resolved.dispositionName
                        ?: suggestedName.takeIf { it.isNotBlank() }
                        ?: urlPathName(resolved.finalUrl)
                        ?: "download"
                when (
                    val decision =
                        BrowserDownloadPolicy.evaluate(
                            DownloadRequest(
                                url = resolved.finalUrl,
                                suggestedName = rawName,
                                mimeType = resolved.contentType,
                                contentLength = resolved.contentLength,
                            ),
                        )
                ) {
                    is DownloadDecision.Denied -> {
                        DownloadOutcome(
                            DownloadToolStatus.REFUSED,
                            "",
                            resolved.finalUrl,
                            "",
                            0L,
                            "",
                            "",
                            denialReason(decision.reason),
                        )
                    }

                    is DownloadDecision.Save -> {
                        // writeArtifactStream requires the target's region dir to pre-exist; ensureLayout
                        // is idempotent (the app bootstrap already did it) so the executor is self-sufficient.
                        workspaceStore.ensureLayout(scopeId)
                        val path = FileScopePath(scopeId, "output/" + decision.targetName)
                        val outcome =
                            workspaceStore.writeArtifactStream(
                                path = path,
                                region = WorkspaceLayout.OUTPUT,
                                expectedBytes = decision.declaredBytes,
                                maxBytes = BrowserDownloadPolicy.MAX_DOWNLOAD_BYTES,
                            ) { out ->
                                resolved.conn.inputStream.use { input -> streamCopied(input, out) }
                            }
                        DownloadOutcome(
                            DownloadToolStatus.SAVED,
                            decision.targetName,
                            resolved.finalUrl,
                            path.toModelReference(),
                            outcome.record.sizeBytes,
                            outcome.record.sha256,
                            resolved.contentType.orEmpty(),
                            "",
                        )
                    }
                }
            } finally {
                resolved.conn.disconnect()
            }
        } catch (e: DownloadRefusal) {
            DownloadOutcome(DownloadToolStatus.REFUSED, "", "", "", 0L, "", "", e.reason)
        } catch (e: AbandonedWrite.LimitExceeded) {
            DownloadOutcome(DownloadToolStatus.REFUSED, "", "", "", 0L, "", "", "size-exceeded")
        } catch (e: SocketTimeoutException) {
            DownloadOutcome(DownloadToolStatus.TIMED_OUT, "", "", "", 0L, "", "", "timed-out")
        } catch (e: Exception) {
            // Fail-closed: any network / disk / quota failure is an ERROR outcome — nothing is
            // published (the store's temp is deleted on any streaming failure).
            DownloadOutcome(DownloadToolStatus.ERROR, "", "", "", 0L, "", "", "download failed")
        }
    }

    /**
     * Manually resolves the download's redirects (auto-follow disabled) so each hop is re-validated
     * as an absolute http(s) URL; a non-http hop, a revisit (loop) or more than [MAX_REDIRECTS]
     * redirects is a [DownloadRefusal]. Returns the open 2xx connection (left connected) plus the
     * response headers the policy gates on. A network [IOException] propagates to the caller's
     * fail-closed handler.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "ThrowsCount", "NestedBlockDepth")
    private fun resolveRedirects(startUrl: String): ResolvedDownload {
        var current = startUrl
        var hops = 0
        val visited = HashSet<String>()
        while (true) {
            if (!BrowserDownloadPolicy.isHttpUrl(current)) throw DownloadRefusal("redirect")
            if (!visited.add(current)) throw DownloadRefusal("redirect-loop")
            val conn = openConnection(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw DownloadRefusal("http-$code")
                    if (++hops > MAX_REDIRECTS) throw DownloadRefusal("redirect-limit")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw DownloadRefusal("http-$code")
                }
                return ResolvedDownload(
                    conn = conn,
                    finalUrl = current,
                    contentType = conn.contentType,
                    contentLength = conn.contentLengthLong,
                    dispositionName = parseDispositionName(conn.getHeaderField("Content-Disposition")),
                )
            } catch (e: DownloadRefusal) {
                // already disconnected on the redirect / non-2xx paths above
                throw e
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

    /** Copies [input] to [out] in bounded chunks; [out] is the store's cap-and-digest guard. */
    private fun streamCopied(
        input: InputStream,
        out: OutputStream,
    ) {
        val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }

    /** Best-effort file name from a URL's path ("" for a bare host); the policy sanitizes it. */
    private fun urlPathName(url: String): String? =
        URL(url)
            .path
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }

    /**
     * Best-effort parse of the Content-Disposition file name (RFC 6266): a `filename*` (RFC 5987)
     * part wins over a plain `filename=` part. The result is a raw hint only — the policy sanitizes
     * and gate-checks it before use, so a malformed or hostile value cannot smuggle a path.
     */
    @Suppress("ReturnCount")
    private fun parseDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(";")
        for (part in parts) {
            val p = part.trim()
            if (!p.startsWith("filename*", ignoreCase = true)) continue
            val value = p.substringAfter('=', missingDelimiterValue = "").trim().trim('\'')
            val afterCharset = value.substringAfter("''", missingDelimiterValue = value)
            val decoded = afterCharset.substringAfter("'", missingDelimiterValue = afterCharset)
            if (decoded.isNotBlank()) return decoded
        }
        for (part in parts) {
            val p = part.trim()
            if (!p.startsWith("filename", ignoreCase = true) || p.startsWith("filename*", ignoreCase = true)) continue
            val value =
                p
                    .substringAfter('=', missingDelimiterValue = "")
                    .trim()
                    .trim('\'')
                    .trim('"')
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** Maps a [DownloadDenial] to the stable model-visible refusal reason. */
    private fun denialReason(denial: DownloadDenial): String =
        when (denial) {
            DownloadDenial.URL -> "url"
            DownloadDenial.UNSAFE_TYPE -> "type"
            DownloadDenial.SIZE -> "size"
            DownloadDenial.NAME -> "name"
        }

    private companion object {
        // browser.download (doc 09 §3.4): bounded redirects + per-socket-idle timeouts + chunk size.
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_CHUNK_SIZE = 64 * 1024
    }

    /** The open 2xx connection (left connected for streaming) plus the policy gate inputs. */
    private data class ResolvedDownload(
        val conn: HttpURLConnection,
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long,
        val dispositionName: String?,
    )

    /**
     * An internal fail-closed download refusal: [reason] is a stable policy/transport category
     * (`url` / `redirect` / `redirect-loop` / `redirect-limit` / `http-<code>`), not a crash. Caught
     * by [download] and mapped to a REFUSED outcome; a network [IOException] is NOT this (it is an
     * ERROR outcome).
     */
    private class DownloadRefusal(
        val reason: String,
    ) : RuntimeException(reason)
}
