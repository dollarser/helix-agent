package com.helix.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class AppUpdateServiceTest {
    @Test fun semanticVersionPrecedenceDoesNotUseVersionCodeOrLexicographicOrder() {
        val versions = listOf("0.0.9", "0.0.10-alpha.2", "0.0.10-alpha.10", "0.0.10-beta", "0.0.10", "0.0.11")
        versions.zipWithNext().forEach { (left, right) -> assertTrue(AppVersion.parse(left) < AppVersion.parse(right)) }
        assertEquals(0, AppVersion.parse("0.0.11+one").compareTo(AppVersion.parse("0.0.11+two")))
        listOf("v0.0.11", "0.11", "0.0.01", "0.0.11-01", "0.0.11-a..b", "0.0.11+").forEach {
            assertThrows(IllegalArgumentException::class.java) { AppVersion.parse(it) }
        }
    }

    @Test fun offersNewerNameWithSameCodeAndMatchesChannelAndAbi() =
        runBlocking {
            val calls = mutableListOf<URI>()
            val response =
                Reply(
                    body = manifest(row("0.0.12", "com.helix.agent"), row("0.0.13"), row("0.0.14", abi = "x86_64")),
                )
            val result =
                service {
                    calls += it
                    response
                }.check() as UpdateCheck.Available
            assertEquals("0.0.13", result.release.versionName)
            assertEquals(1, calls.size)
            assertEquals(UPDATE_MANIFEST_URL, calls.single().toString())
            assertTrue(response.disconnected)
        }

    @Test fun equalOlderAndUnmatchedReleasesAreNotUpdates() =
        runBlocking {
            assertEquals(UpdateCheck.Current, service { Reply(body = manifest(row("0.0.11"))) }.check())
            assertEquals(UpdateCheck.Current, service { Reply(body = manifest(row("0.0.10"))) }.check())
            assertEquals(
                UpdateCheck.NoCompatibleRelease,
                service { Reply(body = manifest(row("0.0.12", abi = "x86_64"))) }.check(),
            )
        }

    @Test fun rejectsUntrustedDownloadDestinationsAndAmbiguousArtifacts() {
        val prefix = "$MODELSCOPE_PROJECT/resolve/master/"
        val invalid =
            listOf(
                "http://modelscope.cn/datasets/dollars/Helix/resolve/master/a.apk",
                "https://modelscope.cn.evil.test/a.apk",
                "https://evil.test@modelscope.cn/datasets/dollars/Helix/resolve/master/a.apk",
                "$prefix../a.apk",
                "$prefix%2e%2e/a.apk",
                "${prefix}a.apk?redirect=evil",
                "${prefix}a.apk#anything",
                "${prefix}a.html",
                "https://modelscope.cn/datasets/other/Helix/resolve/master/a.apk",
            )
        invalid.forEach { assertFalse(it, UpdateManifest.isDownloadUrl(it)) }
        assertTrue(UpdateManifest.isDownloadUrl("${prefix}releases/v0.0.12/helix-advanced-arm64.apk"))
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(manifest(row(), row())) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest(row()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }

    @Test fun redirectsRemainHttpsModelScopeAndAreBounded() =
        runBlocking {
            val calls = mutableListOf<URI>()
            val result =
                service { url ->
                    calls += url
                    if (calls.size == 1) {
                        Reply(302, location = "https://cdn.modelscope.cn/manifest")
                    } else {
                        Reply(body = manifest(row()))
                    }
                }.check()
            assertEquals(UpdateCheck.Current, result)
            assertEquals(2, calls.size)
            val untrusted =
                listOf(
                    "http://modelscope.cn/file",
                    "https://evil.test/file",
                    "https://modelscope.cn.evil.test/file",
                )
            untrusted.forEach { target ->
                var count = 0
                failure(UpdateFailure.INVALID_MANIFEST) {
                    service {
                        count++
                        Reply(302, location = target)
                    }.check()
                }
                assertEquals(1, count)
            }
            var count = 0
            failure(UpdateFailure.INVALID_MANIFEST) {
                service {
                    count++
                    Reply(302, location = UPDATE_MANIFEST_URL)
                }.check()
            }
            assertEquals(5, count)
        }

    @Test fun failuresRemainVisibleAndNoDownloadIsTriggered() =
        runBlocking {
            failure(UpdateFailure.NOT_PUBLISHED) { service { Reply(404) }.check() }
            failure(UpdateFailure.NETWORK) { service { throw IOException("offline") }.check() }
            failure(UpdateFailure.NETWORK) { service { Reply(503) }.check() }
            val invalidBodies =
                listOf(
                    "<html>Login</html>",
                    "{}",
                    "{\"schemaVersion\":1,\"releases\":[]}",
                    "x".repeat(65537),
                )
            invalidBodies.forEach { body ->
                failure(UpdateFailure.INVALID_MANIFEST) { service { Reply(body = body) }.check() }
            }
            failure(UpdateFailure.UNSUPPORTED_VERSION) {
                val invalid =
                    AppUpdateService("unknown", "com.helix.agent", listOf("arm64-v8a")) {
                        error("must not request")
                    }
                invalid.check()
            }
        }

    @Test fun cancellationIsNotReportedAsNetworkFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking { service { throw CancellationException("dismissed") }.check() }
        }
    }

    private fun service(factory: (URI) -> HttpURLConnection) =
        AppUpdateService("0.0.11", "com.helix.agent.developer", listOf("arm64-v8a"), factory)

    private suspend fun failure(
        reason: UpdateFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            error("Expected $reason")
        } catch (failure: UpdateCheckException) {
            assertEquals(reason, failure.reason)
        }
    }

    private class Reply(
        private val code: Int = 200,
        private val body: String = "",
        private val location: String? = null,
    ) : HttpURLConnection(URI(UPDATE_MANIFEST_URL).toURL()) {
        var disconnected = false

        override fun disconnect() {
            disconnected = true
        }

        override fun connect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())

        override fun getContentLengthLong() = -1L

        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
    }

    private fun manifest(vararg rows: String) = """{"schemaVersion":1,"releases":[${rows.joinToString(",")}]}"""

    private fun row(
        version: String = "0.0.11",
        pkg: String = "com.helix.agent.developer",
        abi: String = "arm64-v8a",
    ) = """
        {"versionName":"$version","versionCode":1,"packageName":"$pkg","abis":["$abi"],
        "apkUrl":"$MODELSCOPE_PROJECT/resolve/master/helix.apk",
        "sha256":"${"a".repeat(64)}","sizeBytes":100}
        """.trimIndent()
}
