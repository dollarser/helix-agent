package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic private store and separate consent preferences; never indexes the owner's library. */
class SemanticLiveModelDeviceTest {
    @Test fun selectedSyntheticLongDocumentUsesRealEmbeddingAndRejectsStaleRevision(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.semanticLive")
            org.junit.Assume.assumeTrue("Explicit real embedding opt-in absent", flag != null)
            require(flag == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            app.account.restore()
            check(app.account.signedIn.value)
            val name = "semantic-live-" + UUID.randomUUID()
            val root = File(app.cacheDir, name).apply { mkdirs() }
            val documents = KnowledgeFixtureDocuments()
            val history = KnowledgeFixtureDocuments()
            val store = KnowledgeStore(documents, history)
            val observer = SyntheticEmbeddingObserver()
            val service = KnowledgeSemanticService(app, store, app.account, name, observer)
            try {
                val sentence = "Ordinary synthetic inventory lists everyday office supplies and stationery. "
                val filler = sentence.repeat(180)
                val evidence = "\nA nautical illumination tower stands at Port Helios. Its site code is SYNTH-48621."
                val body = filler + evidence
                val now = System.currentTimeMillis()
                store.saveNote(KnowledgeNote("selected", "Synthetic field report", body, now, now))
                store.saveNote(KnowledgeNote("private", "Unselected", "UNSELECTED_EMBEDDING_SENTINEL", now, now))
                val model = service.models().single { it == "Qwen/Qwen3-Embedding-0.6B" }
                val prefix = "Instruct: Given a question, retrieve relevant passages that answer it.\nQuery:"
                service.apply(SemanticConfiguration(model, prefix, keys = setOf("note:selected")))
                withTimeout(120_000) { service.status.first { it == "READY" || it == "ERROR" } }
                check(service.status.value == "READY") { "Synthetic semantic indexing failed" }
                verifyRetrieval(service, store, observer, body)
                File(app.cacheDir, "semantic-live-result.json").writeText(
                    buildJsonObject {
                        put("model", model)
                        put("longTailRetrieved", true)
                        put("selectedOnly", true)
                        put("staleRevisionRejected", true)
                        put("embeddingInputs", observer.inputs.size)
                        put("syntheticOnly", true)
                    }.toString(),
                )
            } finally {
                service.stopAndWait()
                service.close()
                app.deleteSharedPreferences(name)
                File(app.cacheDir, "$name-vectors").deleteRecursively()
                root.deleteRecursively()
            }
        }

    private suspend fun verifyRetrieval(
        service: KnowledgeSemanticService,
        store: KnowledgeStore,
        observer: SyntheticEmbeddingObserver,
        body: String,
    ) {
        val reference =
            KnowledgeReference(
                "NOTE",
                "selected",
                "Synthetic",
                knowledgeHash(body),
                body.length,
                "USER_CONTENT",
            )
        val hits = service.search("Identify the coastal beacon location.", listOf(reference))
        assertTrue(
            "Real embedding must rank the non-literal long-tail passage first",
            hits.firstOrNull()?.let {
                it.start > 12_000 && SemanticIndex.text(it.document, it.start, it.end).contains("SYNTH-48621")
            } == true,
        )
        assertTrue(hits.all { it.document.key == "note:selected" })
        assertFalse(observer.inputs.any { it.contains("UNSELECTED_EMBEDDING_SENTINEL") })
        val count = observer.inputs.size
        assertTrue(service.search("coastal beacon", listOf(reference.copy(resourceId = "private"))).isEmpty())
        assertTrue(observer.inputs.size == count)
        val current = requireNotNull(store.note("selected"))
        store.saveNote(current.copy(body = "Edited synthetic revision"))
        assertTrue(service.search("coastal beacon", listOf(reference)).isEmpty())
    }
}

private class SyntheticEmbeddingObserver : WireClient {
    private val delegate = OkHttpWireClient(followRedirects = false)
    val inputs = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun open(request: WireRequest): WireResponse {
        val root = Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject
        inputs += (root.getValue("input") as JsonArray).map { it.jsonPrimitive.content }
        return delegate.open(request)
    }
}
