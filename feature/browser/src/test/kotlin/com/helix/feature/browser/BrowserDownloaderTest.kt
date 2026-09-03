package com.helix.feature.browser

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.DownloadToolStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

/**
 * HXA-063 (verification-matrix row `:feature:browser:testDebugUnitTest`): the browser download
 * executor driven END-TO-END on the host JVM. [BrowserDownloader] is pure JVM (no WebView, no
 * cleartext policy), so a real `java.net.HttpURLConnection` against a local [ServerSocket] plus a
 * real [WorkspaceArtifactStore] over a temp dir exercises the full production path: manual
 * redirect re-validation, the [BrowserDownloadPolicy] gates, and the capped streaming write.
 *
 * Covered here: a SAVED download lands in the Workspace with the exact bytes + SHA-256 (the server
 * Content-Disposition name wins); a redirect resolves then saves; a redirect LOOP, a redirect chain
 * over the redirect limit, and a hop to a non-http scheme are all REFUSED with their stable reason;
 * a blocked Android MIME is REFUSED `type`; a non-http top-level URL is REFUSED `url`; and an
 * unreachable host is an ERROR with nothing published. The declared-100-MiB size gate is pinned by
 * [BrowserDownloadPolicyTest] (the cap is a fixed policy constant, unreachable by a small stream),
 * and the streaming cap itself is pinned by the :core:workspace `writeArtifactStream` tests.
 */
class BrowserDownloaderTest {
    private lateinit var storeDir: File
    private var server: MiniHttpServer? = null

    private fun downloader(): BrowserDownloader =
        BrowserDownloader(WorkspaceArtifactStore(ScopeRootResolver { storeDir.toPath() }), "testscope")

    @Before
    fun setUp() {
        storeDir = Files.createTempDirectory("hxa063-download").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        storeDir.deleteRecursively()
    }

    private fun serve(routes: Map<String, MiniHttpServer.Route>) {
        server = MiniHttpServer(routes)
    }

    private fun url(path: String): String = "http://127.0.0.1:${server!!.port}$path"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun filesNamed(name: String): List<File> =
        storeDir.walkTopDown().filter { it.isFile && it.name == name }.toList()

    // ── SAVED ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aSavedDownloadLandsInTheWorkspaceWithTheExactBytesAndSha() {
        val bytes = "helix-download-bytes-0123456789".toByteArray()
        serve(
            mapOf(
                "/report.bin" to
                    MiniHttpServer.Route(
                        status = 200,
                        contentType = "application/octet-stream",
                        body = bytes,
                        location = null,
                        extraHeaders = mapOf("Content-Disposition" to "attachment; filename=\"report.bin\""),
                    ),
            ),
        )
        val out = downloader().download(url("/report.bin"), "ignored-hint.bin")
        assertEquals(DownloadToolStatus.SAVED, out.status)
        assertEquals("report.bin", out.fileName)
        assertEquals(bytes.size.toLong(), out.sizeBytes)
        assertEquals(sha256Hex(bytes), out.sha256)
        assertEquals("application/octet-stream", out.contentType)
        assertEquals("", out.reason)
        assertTrue("the reference must name the saved file", out.reference.contains("report.bin"))
        val saved = filesNamed("report.bin").single()
        assertEquals(sha256Hex(bytes), sha256Hex(saved.readBytes()))
    }

    @Test
    fun aRedirectIsResolvedThenTheTargetIsSaved() {
        val bytes = "after-redirect".toByteArray()
        serve(
            mapOf(
                "/redirect-ok" to MiniHttpServer.Route(302, null, ByteArray(0), "/report.bin"),
                "/report.bin" to MiniHttpServer.Route(200, "application/octet-stream", bytes, null),
            ),
        )
        val out = downloader().download(url("/redirect-ok"), "")
        assertEquals(DownloadToolStatus.SAVED, out.status)
        assertEquals("report.bin", out.fileName)
        assertTrue("the final URL must be the resolved target", out.finalUrl.endsWith("/report.bin"))
        assertEquals(sha256Hex(bytes), out.sha256)
    }

    // ── redirect fail-closed ─────────────────────────────────────────────────────────────

    @Test
    fun aRedirectLoopIsRefused() {
        serve(
            mapOf(
                "/loop-a" to MiniHttpServer.Route(302, null, ByteArray(0), "/loop-b"),
                "/loop-b" to MiniHttpServer.Route(302, null, ByteArray(0), "/loop-a"),
            ),
        )
        val out = downloader().download(url("/loop-a"), "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("redirect-loop", out.reason)
        assertTrue("nothing may be published on a refused download", filesNamed("x").isEmpty())
    }

    @Test
    fun aRedirectChainOverTheLimitIsRefused() {
        val routes =
            (1..6).associate {
                "/chain-$it" to
                    MiniHttpServer.Route(302, null, ByteArray(0), "/chain-${it + 1}")
            }
        serve(routes)
        val out = downloader().download(url("/chain-1"), "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("redirect-limit", out.reason)
    }

    @Test
    fun aRedirectToANonHttpSchemeIsRefused() {
        serve(mapOf("/nonhttp" to MiniHttpServer.Route(302, null, ByteArray(0), "file:///etc/passwd")))
        val out = downloader().download(url("/nonhttp"), "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("redirect", out.reason)
    }

    // ── policy gates ─────────────────────────────────────────────────────────────────────

    @Test
    fun aBlockedAndroidMimeIsRefusedAsType() {
        serve(
            mapOf(
                "/badtype" to
                    MiniHttpServer.Route(200, "application/vnd.android.package-archive", "x".toByteArray(), null),
            ),
        )
        val out = downloader().download(url("/badtype"), "")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("type", out.reason)
        assertTrue("a refused download must not publish a file", filesNamed("badtype").isEmpty())
    }

    @Test
    fun aNonHttpTopLevelUrlIsRefusedAsUrl() {
        val out = downloader().download("file:///etc/passwd", "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("url", out.reason)
    }

    @Test
    fun anUnreachableHostIsAnErrorWithNothingPublished() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val out = downloader().download("http://127.0.0.1:$deadPort/nope", "nope.bin")
        assertEquals(DownloadToolStatus.ERROR, out.status)
        assertEquals("download failed", out.reason)
        assertTrue(filesNamed("nope.bin").isEmpty())
    }

    // ── name precedence (disposition > suggestedName > URL path > "download") ───────────

    @Test
    fun theSuggestedNameIsUsedWhenThereIsNoDispositionOrPathName() {
        serve(mapOf("/" to MiniHttpServer.Route(200, "application/octet-stream", "z".toByteArray(), null)))
        val out = downloader().download(url("/"), "my-file.txt")
        assertEquals(DownloadToolStatus.SAVED, out.status)
        assertEquals("my-file.txt", out.fileName)
    }

    @Test
    fun theUrlPathNameIsUsedWhenThereIsNoDispositionOrSuggestedName() {
        serve(mapOf("/files/data.csv" to MiniHttpServer.Route(200, "text/csv", "a,b".toByteArray(), null)))
        val out = downloader().download(url("/files/data.csv"), "")
        assertEquals(DownloadToolStatus.SAVED, out.status)
        assertEquals("data.csv", out.fileName)
    }

    @Test
    fun theDefaultNameIsUsedForABareHostUrl() {
        serve(mapOf("/" to MiniHttpServer.Route(200, "application/octet-stream", "z".toByteArray(), null)))
        val out = downloader().download(url("/"), "")
        assertEquals(DownloadToolStatus.SAVED, out.status)
        assertEquals("download", out.fileName)
    }

    /**
     * A minimal single-thread loopback HTTP/1.1 server for hermetic download tests. Serves a fixed
     * route map (request path → status / headers / body); each request is one connection, and the
     * response declares `Connection: close`.
     */
    @Suppress("SwallowedException") // loopback test server: best-effort socket cleanup, a client abort is expected
    private class MiniHttpServer(
        private val routes: Map<String, Route>,
    ) : AutoCloseable {
        class Route(
            val status: Int,
            val contentType: String?,
            val body: ByteArray,
            val location: String?,
            val extraHeaders: Map<String, String> = emptyMap(),
        )

        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val thread =
            Thread(::serve, "hxa063-mini-http").apply {
                isDaemon = true
                start()
            }

        private fun serve() {
            while (true) {
                val sock =
                    try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        return
                    }
                handle(sock)
            }
        }

        private fun handle(sock: Socket) {
            try {
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val path = requestPath(input) ?: return
                val route = routes[path] ?: Route(404, "text/plain", ByteArray(0), null)
                val head =
                    StringBuilder()
                        .append("HTTP/1.1 ")
                        .append(route.status)
                        .append(' ')
                        .append(statusText(route.status))
                        .append("\r\n")
                route.location?.let { head.append("Location: ").append(it).append("\r\n") }
                route.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
                for ((k, v) in route.extraHeaders) {
                    head
                        .append(k)
                        .append(": ")
                        .append(v)
                        .append("\r\n")
                }
                head.append("Content-Length: ").append(route.body.size).append("\r\n")
                head.append("Connection: close\r\n\r\n")
                output.write(head.toString().toByteArray())
                output.write(route.body)
                output.flush()
            } catch (e: Exception) {
                // The client aborted (a refusal disconnects mid-stream) — ignore.
            } finally {
                try {
                    sock.close()
                } catch (e: Exception) {
                    // already closed
                }
            }
        }

        private fun requestPath(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            return line
                .toString()
                .substringAfter(" ", missingDelimiterValue = "")
                .substringBefore(" ")
                .ifEmpty { null }
        }

        override fun close() {
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // already closed
            }
            thread.join(2_000)
        }

        private fun statusText(code: Int): String =
            when (code) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                else -> "-"
            }
    }
}
