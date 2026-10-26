package com.helix.app.knowledge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class KnowledgeCollectionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
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
            service.saveSummary(item, "Reviewed summary 独立摘要")
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
            assertEquals("Synthetic material 知识引用", service.originalBytes(textItem, 0).toString(Charsets.UTF_8))
            val originalUri =
                service.originalUri(textItem, 0)
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
            assertTrue(service.originalBytes(imageItem, 0).isNotEmpty())
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
            assertTrue(service.originalBytes(item, 0).toString(Charsets.UTF_8).contains("Synthetic"))
            service.capture(text("x".repeat(200_001)), "fixture").join()
            assertTrue(service.captureError.value)
            assertEquals(1, service.items.first().size)
        }

    @Test
    fun retryFailedExtraction() =
        fixture { service, store, documents ->
            val item =
                CollectedSource(
                    UUID.randomUUID().toString(),
                    "failure",
                    1,
                    "fixture",
                    "",
                    "kept",
                    filesJson =
                        "[{\"name\":\"fixture.txt\",\"mime\":\"text/plain\"," +
                            "\"reference\":\"9223372036854775807\"}]",
                    state = "QUEUED",
                )
            store.insert(item)
            service.retry(requireNotNull(store.get(item.id))).join()
            assertEquals("FAILED", store.get(item.id)?.state)
            val original =
                documents.save(
                    "originals/${item.id}",
                    "0.txt",
                    "recovered content".toByteArray(),
                    mimeType = "text/plain",
                )
            val current = requireNotNull(store.get(item.id))
            store.save(
                current.copy(
                    filesJson =
                        org.json
                            .JSONArray(current.filesJson)
                            .apply {
                                getJSONObject(0).put("reference", original.reference)
                            }.toString(),
                ),
            )
            service.retry(requireNotNull(store.get(item.id))).join()
            assertEquals("READY", store.get(item.id)?.state)
            assertTrue(requireNotNull(store.get(item.id)).extracted.contains("recovered content"))
        }

    @Test
    fun startupMarksSharedInterruptedStatesWithoutAutomaticallyExtracting() =
        runBlocking {
            val name = "knowledge-test-${UUID.randomUUID()}.db"
            val store = KnowledgeStore(context, name)
            val first = CollectedSource("interrupted", "Interrupted", 1, "fixture", "", "original")
            val second = first.copy(id = "queued", state = "QUEUED")
            store.insert(first)
            store.insert(second)
            val service = KnowledgeService(context, fakeFetch(), store)
            try {
                service.recovery.join()
                assertEquals("import-interrupted", store.get(first.id)?.detail)
                assertEquals("original", store.get(first.id)?.rawText)
                assertEquals("INCOMPLETE", store.get(second.id)?.state)
                assertEquals("", store.get(second.id)?.extracted)
                service.retry(requireNotNull(store.get(second.id))).join()
                assertEquals("READY", store.get(second.id)?.state)
                assertEquals("original", store.get(second.id)?.extracted)
            } finally {
                service.close()
                clearKnowledgeDocuments(context, name)
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
            KnowledgeExtractor(store, fakeFetch(403)).extract(item)
            assertEquals("link-unavailable", store.get(item.id)?.detail)
            assertEquals("https://example.org/article", store.get(item.id)?.rawText)
        }

    @Test
    fun cancelledExtractionRecovers() =
        fixture { service, store, _ ->
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
            store.insert(item)
            val cancels =
                object : HttpFetchBridge {
                    override fun fetch(request: HttpFetchRequest): HttpFetchOutcome =
                        throw CancellationException("fixture")
                }
            try {
                KnowledgeExtractor(store, cancels).extract(requireNotNull(store.get(item.id)))
                throw AssertionError("Cancellation must propagate")
            } catch (_: CancellationException) {
                // The durable active state must remain recoverable.
            }
            assertEquals("EXTRACTING", store.get(item.id)?.state)
            service.retry(requireNotNull(store.get(item.id))).join()
            assertEquals("INCOMPLETE", store.get(item.id)?.state)
            assertTrue(requireNotNull(store.get(item.id)).extracted.contains("Public article"))
        }

    @Test
    fun externalSenderReachesExportedReceiverWithTemporaryContentGrant(): Unit =
        runBlocking {
            receiveExternal(chooser = false)
        }

    @Test
    fun systemSharesheetSelectsHelixAndRetainsOriginalAfterGrantRevocation(): Unit =
        runBlocking {
            receiveExternal(chooser = true)
        }

    private suspend fun receiveExternal(chooser: Boolean) {
        val app = context.applicationContext as HelixApplication
        val service = app.knowledge
        val token = "external-${UUID.randomUUID()}"
        val component =
            Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .setPackage(context.packageName)
                .resolveActivity(context.packageManager)
        assertEquals(KnowledgeCaptureActivity::class.java.name, component?.className)
        val outgoing = sender(token).putExtra("text", token).putExtra("chooser", chooser)
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
        if (chooser) selectHelixFromSharesheet()
        val row =
            withTimeout(30_000) {
                service.items.first { rows -> rows.any { it.rawText == token && it.state == "READY" } }
            }.first { it.rawText == token }
        try {
            assertTrue(service.originalBytes(row, 0).toString(Charsets.UTF_8).contains("知识引用"))
            assertEquals("READY", row.state)
            val seen = app.awaitAppContainer().firstLaunch.noticeSeen
            assertReceivedEntry(seen, token)
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
            assertTrue(service.originalBytes(row, 0).toString(Charsets.UTF_8).contains("知识引用"))
        } finally {
            service.remove(row.id)
        }
    }

    private fun assertReceivedEntry(
        seen: Boolean,
        token: String,
    ) {
        // Assert the actual Compose surface, not application-context locale text in a
        // possibly different accessibility window. Do not accept/dismiss the notice.
        compose.waitUntil(15_000) {
            if (seen) {
                compose.onAllNodesWithText(token, substring = true).fetchSemanticsNodes().isNotEmpty()
            } else {
                compose.onAllNodesWithTag("first-launch-notice").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (seen) {
            compose.onAllNodesWithText(token, substring = true)[0].assertIsDisplayed()
        } else {
            compose.onNodeWithTag("first-launch-notice").assertIsDisplayed()
            compose.onNodeWithTag("first-launch-continue").assertIsDisplayed()
        }
    }

    private suspend fun selectHelixFromSharesheet() {
        val label =
            context.packageManager
                .getActivityInfo(
                    android.content.ComponentName(context, KnowledgeCaptureActivity::class.java),
                    0,
                ).loadLabel(context.packageManager)
                .toString()
        withTimeout(15_000) {
            while (!selectShareTarget(label)) kotlinx.coroutines.delay(50)
        }
    }

    private fun selectShareTarget(label: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root == null || !root.packageName.toString().contains("intentresolver")) return false
        val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        var clicked = false
        while (pending.isNotEmpty() && visited++ < 500 && !clicked) {
            val node = pending.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                var target: android.view.accessibility.AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                clicked = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return clicked
    }

    @Test
    fun publicExamplePageUsesRealTransport() =
        fixture(realFetch = true) { service, store, _ ->
            service.capture(text("https://example.com"), "public fixture").join()
            val item = service.items.first().single()
            android.util.Log.i("HelixKnowledgeEvidence", "public-example state=${item.state} detail=${item.detail}")
            assertEquals("https://example.com", item.sourceUrl)
            assertTrue(
                "Public page must have actual extracted body text",
                item.extracted.contains("documentation examples"),
            )
            assertTrue(item.detail.contains("public-page-unverified"))
            assertTrue(store.documents.list("originals/${item.id}").any { it.name.startsWith("public-page") })
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
        block: suspend (KnowledgeService, KnowledgeStore, com.helix.core.storage.content.DocumentStorage) -> Unit,
    ) = runBlocking {
        val name = "knowledge-test-${UUID.randomUUID()}.db"
        val store = KnowledgeStore(context, name)
        val service =
            if (realFetch) {
                KnowledgeService(context, store = store)
            } else {
                KnowledgeService(context, fakeFetch(), store)
            }
        service.recovery.join()
        try {
            block(service, store, store.documents)
        } finally {
            service.close()
            clearKnowledgeDocuments(context, name)
        }
    }
}
