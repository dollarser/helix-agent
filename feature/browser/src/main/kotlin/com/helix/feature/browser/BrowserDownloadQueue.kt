package com.helix.feature.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class BrowserDownloadQueue(
    private val appContext: Context,
) {
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())

    private val downloadExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "helix-browser-download").apply { isDaemon = true }
        }

    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    /**
     * Entry point for the WebView download seam (and the on-device test): runs
     * [BrowserDownloadPolicy] and queues the result. A denial is queued as a visible,
     * DENIED row — no bytes are ever fetched for a denied item.
     */
    fun requestDownload(request: DownloadRequest) {
        when (val decision = BrowserDownloadPolicy.evaluate(request)) {
            is DownloadDecision.Save -> {
                _downloads.value =
                    _downloads.value +
                    DownloadItem(
                        id = newDownloadId(),
                        url = request.url,
                        fileName = decision.targetName,
                        declaredBytes = decision.declaredBytes,
                        status = DownloadStatus.PENDING_CHOICE,
                    )
            }

            is DownloadDecision.Denied -> {
                _downloads.value =
                    _downloads.value +
                    DownloadItem(
                        id = newDownloadId(),
                        url = request.url,
                        fileName =
                            BrowserDownloadPolicy.sanitizeName(request.suggestedName).ifBlank { "download" },
                        declaredBytes = if (request.contentLength > 0) request.contentLength else -1L,
                        status = DownloadStatus.DENIED,
                        denial = decision.reason,
                    )
            }
        }
    }

    /**
     * Streams the queued item into the user-picked SAF [documentUri]. The URL is
     * re-verified as http(s) at execution time (the queue is not a trust boundary,
     * doc 09 §3.4) and the same 100 MiB cap the policy uses is enforced byte-for-byte
     * when the declared length is unknown.
     *
     * One fail-closed return per guard (unknown item / not pending / non-http URL).
     */
    @Suppress("ReturnCount")
    fun saveDownload(
        itemId: String,
        documentUri: Uri,
    ) {
        val item = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (item.status != DownloadStatus.PENDING_CHOICE) return
        if (!BrowserDownloadPolicy.isHttpUrl(item.url)) {
            markDownload(itemId, DownloadStatus.DENIED, denial = DownloadDenial.URL)
            return
        }
        markDownload(itemId, DownloadStatus.SAVING)
        downloadExecutor.execute {
            val (status, detail) = streamToDocument(item, documentUri)
            // StateFlow is synchronized; this is the single sanctioned off-main-thread
            // write in this class.
            markDownload(itemId, status, detail = detail)
        }
    }

    /** Removes a finished / denied row; an in-flight SAVING row cannot be interrupted. */
    fun dismissDownload(itemId: String) {
        val item = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (item.status == DownloadStatus.SAVING) return
        _downloads.value = _downloads.value.filterNot { it.id == itemId }
    }

    /** One fail-closed return per guard (non-2xx response / unopenable document). */
    @Suppress("ReturnCount")
    private fun streamToDocument(
        item: DownloadItem,
        documentUri: Uri,
    ): Pair<DownloadStatus, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(item.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return DownloadStatus.FAILED to "HTTP $responseCode"
            val out =
                appContext.contentResolver.openOutputStream(documentUri)
                    ?: return DownloadStatus.FAILED to appContext.getString(R.string.browser_download_cannot_open)
            out.use { output -> copyWithCap(connection.inputStream, output) ?: DownloadStatus.SAVED to null }
        } catch (e: IOException) {
            DownloadStatus.FAILED to (e.message ?: appContext.getString(R.string.browser_download_failed_generic))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Copies [input] to [output] in 64 KiB chunks, enforcing the policy's 100 MiB cap
     * byte-for-byte (the declared length is not a trust boundary, doc 09 §3.4). A
     * non-null result is the cap-breach FAILED outcome; null means the copy completed.
     */
    private fun copyWithCap(
        input: InputStream,
        output: OutputStream,
    ): Pair<DownloadStatus, String?>? {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return null
            written += read
            if (written > BrowserDownloadPolicy.MAX_DOWNLOAD_BYTES) {
                return DownloadStatus.FAILED to appContext.getString(R.string.browser_download_over_cap)
            }
            output.write(buffer, 0, read)
        }
    }

    private fun markDownload(
        itemId: String,
        status: DownloadStatus,
        denial: DownloadDenial? = null,
        detail: String? = null,
    ) {
        _downloads.value =
            _downloads.value.map {
                if (it.id == itemId) {
                    it.copy(
                        status = status,
                        denial = denial ?: it.denial,
                        detail = detail ?: it.detail,
                    )
                } else {
                    it
                }
            }
    }

    private fun newDownloadId(): String = "dl-" + UUID.randomUUID()
}
