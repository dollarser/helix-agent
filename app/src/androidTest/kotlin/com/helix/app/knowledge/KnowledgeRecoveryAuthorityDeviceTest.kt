package com.helix.app.knowledge

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.tools.android.HttpFetchBridge
import com.helix.tools.android.HttpFetchOutcome
import com.helix.tools.android.HttpFetchRequest
import com.helix.tools.android.HttpFetchStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real shared synthetic documents; no microphone, remote request, external app or actual account. */
class KnowledgeRecoveryAuthorityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun editedSharedStatesCannotAuthorizeStartupDeletionOrExtraction() =
        runBlocking {
            fixture { store, fetches, service ->
                val states = listOf("DELETING", "QUEUED", "EXTRACTING", "IMPORTING")
                states.forEachIndexed { index, state ->
                    val item = source("source-$index")
                    store.insert(item)
                    store.documents.save(
                        "originals/${item.id}",
                        "0.txt",
                        "keep-$index".toByteArray(),
                        mimeType = "text/plain",
                    )
                    edit(store, item.id, "\"state\":\"READY\"", "\"state\":\"$state\"")
                }
                val running = service(store)
                try {
                    running.recovery.join()
                    assertFalse(running.storageError.value)
                    assertEquals(0, fetches.get())
                    states.indices.forEach { index ->
                        val item = store.get("source-$index")
                        assertNotNull(item)
                        assertEquals("INCOMPLETE", item!!.state)
                        assertEquals("raw", item.rawText)
                        val reference = store.documents.references("originals/${item.id}").single()
                        assertEquals(
                            "keep-$index",
                            store.documents
                                .read(reference, 4096)
                                .bytes
                                .toString(Charsets.UTF_8),
                        )
                    }
                    running.retry(requireNotNull(store.get("source-1"))).join()
                    assertEquals(1, fetches.get())
                    assertTrue(requireNotNull(store.get("source-1")).extracted.contains("Synthetic article"))
                    running.remove("source-0", requireNotNull(store.get("source-0")))
                    assertEquals(null, store.get("source-0"))
                    assertTrue(store.documents.references("originals/source-0").isEmpty())
                    assertNotNull(store.get("source-2"))
                } finally {
                    running.close()
                }
            }
        }

    @Test fun crossSourceOriginalReferencesCannotBeReadOpenedOrExtracted() =
        runBlocking {
            fixture { store, fetches, service ->
                val other =
                    store.documents.save(
                        "originals/other",
                        "0.txt",
                        "other bytes".toByteArray(),
                        mimeType = "text/plain",
                    )
                val own =
                    store.documents.save(
                        "originals/selected",
                        "0.txt",
                        "own bytes".toByteArray(),
                        mimeType = "text/plain",
                    )
                store.insert(source("other"))
                val files =
                    JSONArray().put(
                        JSONObject()
                            .put("name", "file.txt")
                            .put("mime", "text/plain")
                            .put("reference", other.reference),
                    )
                store.insert(source("selected").copy(filesJson = files.toString()))
                val running = service(store)
                try {
                    running.recovery.join()
                    val selected = requireNotNull(store.get("selected"))
                    denied { running.originalBytes(selected, 0) }
                    denied { running.originalUri(selected, 0) }
                    running.retry(selected).join()
                    assertEquals("FAILED", store.get("selected")!!.state)
                    assertEquals(0, fetches.get())
                    running.remove("selected", requireNotNull(store.get("selected")))
                    assertEquals(
                        "other bytes",
                        store.documents
                            .read(other.reference, 4096)
                            .bytes
                            .toString(Charsets.UTF_8),
                    )
                    assertTrue(own.reference !in store.documents.references("originals/selected"))
                } finally {
                    running.close()
                }
            }
        }

    @Test fun staleExplicitRetryCannotFollowAnExternallyChangedTarget() =
        runBlocking {
            fixture { store, fetches, service ->
                store.insert(source("selected"))
                val running = service(store)
                try {
                    running.recovery.join()
                    val selected = requireNotNull(store.get("selected"))
                    edit(store, "selected", "never-fetch", "changed-target")
                    running.retry(selected).join()
                    assertEquals(0, fetches.get())
                    assertEquals("READY", store.get("selected")!!.state)
                    assertTrue(running.storageError.value)
                    running.retry(requireNotNull(store.get("selected"))).join()
                    assertEquals(1, fetches.get())
                } finally {
                    running.close()
                }
            }
        }

    @Test fun unreadableStartupFailsVisiblyWithoutDeletingOrFetching() =
        runBlocking {
            fixture { store, fetches, service ->
                store.insert(source("selected").copy(state = "QUEUED"))
                val before = store.documents.list("sources").single()
                val unreadable =
                    java.util.concurrent.atomic
                        .AtomicBoolean(true)
                val failing =
                    object : DocumentStorage by store.documents {
                        override fun list(directory: String): List<DocumentStorage.File> {
                            if (unreadable.get()) throw SecurityException("synthetic lost access")
                            return store.documents.list(directory)
                        }
                    }
                // No history is written in this failure-only fixture.
                val running = service(KnowledgeStore(failing, store.documents))
                try {
                    running.recovery.join()
                    assertTrue(running.storageError.value)
                    assertEquals(0, fetches.get())
                    assertEquals(before.hash, store.documents.read(before.reference, 4096).hash)
                    unreadable.set(false)
                    running.retryStartup()
                    assertFalse(running.storageError.value)
                    assertEquals(
                        "INCOMPLETE",
                        running.items.value
                            .single()
                            .state,
                    )
                    assertEquals(0, fetches.get())
                    assertEquals(
                        "selected",
                        running.items.value
                            .single()
                            .id,
                    )
                } finally {
                    running.close()
                }
            }
        }

    private fun source(id: String) =
        CollectedSource(
            id,
            "Synthetic source",
            1,
            "fixture",
            "https://example.invalid/never-fetch",
            "raw",
            state = "READY",
        )

    @Test fun duplicateSharedQueueFlagCannotCreateAnotherExtraction() =
        runBlocking {
            fixture { store, fetches, service ->
                val running = service(store)
                try {
                    val intent =
                        android.content
                            .Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_TEXT, "https://example.invalid/never-fetch")
                    running.capture(intent, "fixture").join()
                    val item = store.items.value.single()
                    assertEquals(1, fetches.get())
                    edit(store, item.id, "\"state\":\"${item.state}\"", "\"state\":\"QUEUED\"")
                    running.capture(intent, "fixture").join()
                    assertEquals(1, fetches.get())
                    assertEquals(
                        item.id,
                        store.items.value
                            .single()
                            .id,
                    )
                } finally {
                    running.close()
                }
            }
        }

    @Test fun alteredDedupTargetCannotDiscardOrRetargetTheIncomingContent() =
        runBlocking {
            fixture { store, fetches, service ->
                val running = service(store)
                try {
                    val url = "https://example.invalid/never-fetch"
                    val intent =
                        android.content
                            .Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_TEXT, url)
                    running.capture(intent, "fixture").join()
                    val original = store.items.value.single()
                    val from = "\"sourceUrl\":\"$url\""
                    val to = "\"sourceUrl\":\"https://example.invalid/changed-target\""
                    edit(store, original.id, from, to)
                    val modified = store.get(original.id)!!
                    edit(store, original.id, "\"state\":\"${modified.state}\"", "\"state\":\"QUEUED\"")
                    running.capture(intent, "fixture").join()
                    assertEquals(2, store.items.value.size)
                    assertEquals(2, fetches.get())
                    assertEquals("QUEUED", store.get(original.id)!!.state)
                    assertEquals(
                        url,
                        store.items.value
                            .single { it.id != original.id }
                            .sourceUrl,
                    )
                } finally {
                    running.close()
                }
            }
        }

    private suspend fun edit(
        store: KnowledgeStore,
        id: String,
        from: String,
        to: String,
    ) {
        val observed = requireNotNull(store.get(id))
        val document = store.documents.list("sources").single { it.hash == observed.documentHash }
        val before = document.bytes.toString(Charsets.UTF_8)
        val after = before.replace(from, to)
        check(before != after)
        val uri =
            ContentUris.withAppendedId(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                document.reference.toLong(),
            )
        context.contentResolver.openOutputStream(uri, "rwt")!!.use { it.write(after.toByteArray()) }
    }

    private fun denied(action: () -> Any) {
        try {
            action()
            fail("Expected source-scope rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    private suspend fun fixture(
        action: suspend (KnowledgeStore, AtomicInteger, (KnowledgeStore) -> KnowledgeService) -> Unit,
    ) {
        check(context.packageName.endsWith(".asrvalidation"))
        val name = "knowledge-authority-${UUID.randomUUID()}"
        val store = KnowledgeStore(context, name)
        val counter = AtomicInteger()
        val fetcher =
            object : HttpFetchBridge {
                override fun fetch(request: HttpFetchRequest): HttpFetchOutcome {
                    counter.incrementAndGet()
                    val body = "<article>Synthetic article ${"x".repeat(240)}</article>"
                    return HttpFetchOutcome(
                        HttpFetchStatus.FETCHED,
                        request.url,
                        200,
                        "text/html",
                        body,
                        body.length.toLong(),
                        false,
                        0,
                        "",
                    )
                }
            }
        try {
            action(store, counter) { KnowledgeService(context, fetcher, it) }
        } finally {
            clearKnowledgeDocuments(context, name)
        }
    }
}
