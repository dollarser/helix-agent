package com.helix.app.knowledge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.tools.android.HttpFetchBridge
import com.helix.tools.android.HttpFetchOutcome
import com.helix.tools.android.HttpFetchRequest
import com.helix.tools.android.HttpFetchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class KnowledgeCollectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val testPackage get() = instrumentation.context.packageName

    @Test
    fun textSearchSummaryAndCitation() =
        fixture { service, store, _ ->
            val intent = text("Synthetic 知识资料 apple orchard")
            service.capture(intent, "synthetic source").join()
            service.capture(intent, "second share").join()
            val rows = service.items.first()
            assertEquals(1, rows.size)
            val item = rows.single()
            assertEquals("READY", item.state)
            assertEquals(item.id, service.search("知识").single().id)
            assertEquals(item.id, service.search("orch").single().id)
            assertTrue(service.search("\" OR *").isEmpty())
            service.saveSummary(item.id, "Reviewed summary 独立摘要")
            assertEquals(item.rawText, store.get(item.id)?.rawText)
            assertEquals(item.extracted, store.get(item.id)?.extracted)
            assertEquals(item.id, service.search("独立摘要").single().id)
            val citation = service.citation(item)
            assertTrue(citation.contains("source:${item.id}"))
            assertTrue(citation.contains("synthetic source"))
            assertTrue(citation.contains("Untrusted reference material"))
        }

    @Test
    fun materializeTextAndImageOriginals() =
        fixture { service, _, _ ->
            val uri = grantFixture("text")
            service.capture(text("caption").putExtra(Intent.EXTRA_STREAM, uri), "fixture").join()
            val textItem = service.items.first().single()
            assertEquals("Synthetic material 知识引用", service.originalFile(textItem, 0).readText())
            val originalUri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    service.originalFile(textItem, 0),
                )
            assertEquals("text/plain", context.contentResolver.getType(originalUri))
            assertTrue(textItem.extracted.contains("知识引用"))
            val image = grantFixture("image")
            service
                .capture(
                    Intent(Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, image),
                    "fixture",
                ).join()
            val imageItem = service.items.first().first { it.id != textItem.id }
            assertEquals("INCOMPLETE", imageItem.state)
            assertEquals("original-only", imageItem.detail)
            assertEquals("Synthetic image", imageItem.title)
            assertEquals(imageItem.id, service.search("Synthetic image").single().id)
            assertTrue(service.originalFile(imageItem, 0).exists())
            assertEquals("", imageItem.extracted)
        }

    @Test
    fun refuseUngrantedAndPrivateUris() =
        fixture { service, _, _ ->
            val uri = Uri.parse("content://$testPackage.knowledgefixture/never-granted")
            service.capture(text("retained caption").putExtra(Intent.EXTRA_STREAM, uri), "fixture").join()
            val item = service.items.first().single()
            assertEquals("INCOMPLETE", item.state)
            assertEquals("retained caption", item.rawText)
            assertEquals("[]", item.filesJson)
            val own = Uri.parse("content://${context.packageName}.fileprovider/workspaces/private.txt")
            service.capture(text("private refusal").putExtra(Intent.EXTRA_STREAM, own), "fixture").join()
            assertEquals(
                "[]",
                service.items
                    .first()
                    .first { it.rawText == "private refusal" }
                    .filesJson,
            )
            val ownUser = Uri.parse("content://0@${context.packageName}.fileprovider/workspaces/private.txt")
            val prefixed = text("user-prefixed refusal").putExtra(Intent.EXTRA_STREAM, ownUser)
            service.capture(prefixed, "fixture").join()
            val refused = service.items.first().first { it.rawText == "user-prefixed refusal" }
            assertEquals("[]", refused.filesJson)
            val invalid = text("bad scheme").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private"))
            service.capture(invalid, "fixture").join()
            assertEquals(
                "[]",
                service.items
                    .first()
                    .first { it.rawText == "bad scheme" }
                    .filesJson,
            )
        }

    @Test
    fun preservePartialOversizeImport() =
        fixture { service, _, _ ->
            val intent =
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "kept after partial failure")
                    .putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        arrayListOf(grantFixture("text"), grantFixture("large")),
                    )
            service.capture(intent, "fixture").join()
            val item = service.items.first().single()
            assertEquals("INCOMPLETE", item.state)
            assertEquals(1, org.json.JSONArray(item.filesJson).length())
            assertTrue(service.originalFile(item, 0).readText().contains("Synthetic"))
            service.capture(text("x".repeat(200_001)), "fixture").join()
            assertTrue(service.captureError.value)
            assertEquals(1, service.items.first().size)
        }

    @Test
    fun retryFailedExtraction() =
        fixture { service, store, root ->
            val item =
                CollectedSource(
                    UUID.randomUUID().toString(),
                    "failure",
                    1,
                    "fixture",
                    "",
                    "kept",
                    filesJson = "[{\"name\":\"fixture.txt\",\"mime\":\"text/plain\",\"stored\":\"0.txt\"}]",
                    state = "QUEUED",
                )
            store.insert(item)
            service.retry(item).join()
            assertEquals("FAILED", store.get(item.id)?.state)
            File(root, item.id).mkdirs()
            File(File(root, item.id), "0.txt").writeText("recovered content")
            service.retry(item).join()
            assertEquals("READY", store.get(item.id)?.state)
            assertTrue(requireNotNull(store.get(item.id)).extracted.contains("recovered content"))
        }

    @Test
    fun startupReconcilesDurableInterruptedAndQueuedStates() =
        runBlocking {
            val name = "knowledge-test-${UUID.randomUUID()}.db"
            val root = File(context.cacheDir, name)
            val store = KnowledgeStore(context, name)
            val first = CollectedSource("interrupted", "Interrupted", 1, "fixture", "", "original")
            val second = first.copy(id = "queued", state = "QUEUED")
            store.insert(first)
            store.insert(second)
            val service = KnowledgeService(context, fakeFetch(), store, root)
            try {
                service.recovery.join()
                assertEquals("import-interrupted", store.get(first.id)?.detail)
                assertEquals("original", store.get(first.id)?.rawText)
                assertEquals("READY", store.get(second.id)?.state)
                assertEquals("original", store.get(second.id)?.extracted)
            } finally {
                service.close()
                context.deleteDatabase(name)
                root.deleteRecursively()
            }
        }

    @Test
    fun publicAndLoginPagesAreIncomplete() =
        fixture { service, store, _ ->
            service.capture(text("https://example.org/article"), "fixture").join()
            val item = service.items.first().single()
            assertEquals("INCOMPLETE", item.state)
            assertTrue(item.detail.contains("public-page-unverified"))
            assertTrue(item.extracted.contains("Public article"))
            assertFalse(item.extracted.contains("secret script"))
            assertTrue(store.get(item.id)?.rawText == "https://example.org/article")
            KnowledgeExtractor(File(context.filesDir, "knowledge-unused"), store, fakeFetch(403)).extract(item)
            assertEquals("link-unavailable", store.get(item.id)?.detail)
            assertEquals("https://example.org/article", store.get(item.id)?.rawText)
        }

    @Test
    fun cancelledExtractionRecovers() =
        fixture { service, store, root ->
            val item =
                CollectedSource(
                    "cancel",
                    "Cancellation",
                    1,
                    "fixture",
                    "https://example.org",
                    "original",
                    state = "QUEUED",
                )
            File(root, item.id).mkdirs()
            store.insert(item)
            val cancels =
                object : HttpFetchBridge {
                    override fun fetch(request: HttpFetchRequest): HttpFetchOutcome =
                        throw CancellationException("fixture")
                }
            try {
                KnowledgeExtractor(root, store, cancels).extract(item)
                throw AssertionError("Cancellation must propagate")
            } catch (_: CancellationException) {
                // The durable active state must remain recoverable.
            }
            assertEquals("EXTRACTING", store.get(item.id)?.state)
            service.retry(item).join()
            assertEquals("INCOMPLETE", store.get(item.id)?.state)
            assertTrue(requireNotNull(store.get(item.id)).extracted.contains("Public article"))
        }

    @Test
    fun externalSenderReachesExportedReceiverWithTemporaryContentGrant() =
        runBlocking {
            val app = context.applicationContext as HelixApplication
            val service = app.knowledge
            val token = "external-${UUID.randomUUID()}"
            val component =
                Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .setPackage(context.packageName)
                    .resolveActivity(context.packageManager)
            assertEquals(KnowledgeCaptureActivity::class.java.name, component?.className)
            val outgoing = sender(token).putExtra("text", token)
            val uri = requireNotNull(outgoing.data)
            assertEquals(
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(
                    uri,
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
            context.startActivity(outgoing)
            val row =
                withTimeout(30_000) {
                    service.items.first { rows -> rows.any { it.rawText == token && it.state == "READY" } }
                }.first { it.rawText == token }
            try {
                assertTrue(service.originalFile(row, 0).readText().contains("知识引用"))
                assertEquals("READY", row.state)
                val seen = app.awaitAppContainer().firstLaunch.noticeSeen
                val expected = if (seen) token else app.getString(com.helix.app.R.string.notice_title)
                android.util.Log.i("HelixKnowledgeEvidence", "external-entry waiting noticeSeen=$seen")
                // Cold app startup can outlast source materialization, especially during host builds.
                withTimeout(15_000) {
                    while (!hasVisibleText(expected)) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                android.util.Log.i("HelixKnowledgeEvidence", "external-entry visible=true noticeSeen=$seen")
                // Android may retain an Activity-stack grant until the task finishes. Revoke from the owner UID.
                context.startActivity(sender(token).putExtra("revokeOnly", true))
                withTimeout(5000) {
                    while (context.checkUriPermission(
                            uri,
                            android.os.Process.myPid(),
                            android.os.Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        ) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                assertTrue(service.originalFile(row, 0).readText().contains("知识引用"))
            } finally {
                service.remove(row.id)
            }
        }

    private fun hasVisibleText(expected: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        root?.let(pending::add)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 500) {
            val node = pending.removeFirst()
            if (node.text?.contains(expected) == true ||
                node.contentDescription?.contains(expected) == true
            ) {
                return true
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return false
    }

    @Test
    fun publicExamplePageUsesRealTransport() =
        fixture(realFetch = true) { service, _, root ->
            service.capture(text("https://example.com"), "public fixture").join()
            val item = service.items.first().single()
            android.util.Log.i("HelixKnowledgeEvidence", "public-example state=${item.state} detail=${item.detail}")
            assertEquals("https://example.com", item.sourceUrl)
            assertTrue(
                "Public page must have actual extracted body text",
                item.extracted.contains("documentation examples"),
            )
            assertTrue(item.detail.contains("public-page-unverified"))
            assertTrue(File(File(root, item.id), "public-page.html").exists())
        }

    private fun text(body: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)

    private fun sender(path: String): Intent =
        Intent()
            .setComponent(
                ComponentName(
                    testPackage,
                    "com.helix.app.test.KnowledgeSenderActivity",
                ),
            ).setData(Uri.parse("content://$testPackage.knowledgefixture/$path"))
            .putExtra("target", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun grantFixture(path: String): Uri {
        val request = sender(path).putExtra("grantOnly", true)
        context.startActivity(request)
        val uri = requireNotNull(request.data)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (context.checkUriPermission(
                uri,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            check(android.os.SystemClock.uptimeMillis() < deadline)
            Thread.sleep(20)
        }
        return uri
    }

    private fun fakeFetch(code: Int = 200) =
        object : HttpFetchBridge {
            override fun fetch(request: HttpFetchRequest) =
                HttpFetchOutcome(
                    HttpFetchStatus.FETCHED,
                    request.url,
                    code,
                    "text/html",
                    "<html><script>secret script</script><article>Public article</article></html>",
                    80,
                    false,
                    0,
                    "",
                )
        }

    private fun fixture(
        realFetch: Boolean = false,
        block: suspend (KnowledgeService, KnowledgeStore, File) -> Unit,
    ) = runBlocking {
        val name = "knowledge-test-${UUID.randomUUID()}.db"
        val store = KnowledgeStore(context, name)
        val root = File(context.filesDir, "knowledge/test-$name")
        val service =
            if (realFetch) {
                KnowledgeService(context, store = store, root = root)
            } else {
                KnowledgeService(context, fakeFetch(), store, root)
            }
        service.recovery.join()
        try {
            block(service, store, root)
        } finally {
            service.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
