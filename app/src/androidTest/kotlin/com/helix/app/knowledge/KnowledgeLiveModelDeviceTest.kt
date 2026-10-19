package com.helix.app.knowledge

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** One explicit, synthetic-only call through the already configured built-in service. */
class KnowledgeLiveModelDeviceTest {
    @Test
    fun liveSyntheticMultiSourceDraft(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val optedIn = InstrumentationRegistry.getArguments().getString("helix.knowledgeLiveModel")
            org.junit.Assume.assumeTrue("Explicit real-model opt-in is absent", optedIn != null)
            require(optedIn == "true")
            val context = instrumentation.targetContext
            val providers = (context.applicationContext as HelixApplication).awaitAppContainer().providerService
            providers.refreshModelCatalog(BuiltInModel.PROVIDER_ID)
            withTimeout(30_000) {
                providers.rows.first { rows ->
                    rows.any {
                        it.id == BuiltInModel.PROVIDER_ID && it.modelSelectable(BuiltInModel.MODEL_ID)
                    }
                }
            }
            val name = "knowledge-live-${UUID.randomUUID()}.db"
            val root = File(context.cacheDir, name)
            val store = KnowledgeStore(context, name)
            val library = KnowledgeService(context, store = store, root = root)
            try {
                val (id, ids) = createSyntheticNote(library)
                val prepared =
                    library.noteService.prepare(
                        id,
                        KnowledgeAnalysis(providers),
                        BuiltInModel.PROVIDER_ID,
                        BuiltInModel.MODEL_ID,
                    )
                library.noteService.generate(prepared).join()
                val result = requireNotNull(store.note(id))
                assertEquals("READY", result.generationState)
                assertEquals("DRAFT", result.state)
                val modelBody = result.generatedDraft.substringBefore("\n\n---\nSources (frozen selected excerpts):")
                assertTrue(modelBody.contains("137"))
                assertTrue(modelBody.contains("TEAM_SYNTH_42"))
                ids.forEach { assertTrue(modelBody.contains("[source:$it]")) }
                val reviewedBody = result.generatedDraft + "\nReviewed synthetic fixture."
                library.noteService.save(id, result.title, reviewedBody, true)
                assertEquals("REVIEWED", store.note(id)?.state)
                assertEquals(2, store.noteSources(id).size)
                val outputHash = knowledgeHash(result.generatedDraft)
                android.util.Log.i(
                    "HelixKnowledgeEvidence",
                    "live-model=${BuiltInModel.MODEL_ID} " +
                        "sources=2 inputHash=${prepared.inputHash} " +
                        "outputHash=$outputHash " +
                        "facts=137,TEAM_SYNTH_42 reviewed=true",
                )
            } finally {
                library.close()
                context.deleteDatabase(name)
                root.deleteRecursively()
            }
        }

    private suspend fun createSyntheticNote(library: KnowledgeService): Pair<String, List<String>> {
        listOf(
            "Synthetic project Harbor has a launch budget of 137 tokens.",
            "Synthetic Harbor owner is TEAM_SYNTH_42. No release date was provided.",
        ).forEach { source ->
            library
                .capture(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, source),
                    "synthetic fixture",
                ).join()
        }
        val ids = library.items.first().map { it.id }
        val id = library.noteService.create(ids, "Synthetic Harbor synthesis")
        library.noteService.save(
            id,
            "Synthetic Harbor synthesis",
            "Write a concise English synthesis of Harbor's budget and owner. " +
                "Preserve literal values from the source excerpts exactly. Cite each supplied ID " +
                "and state the missing release date. These are synthetic facts.",
            false,
        )
        return id to ids
    }
}
