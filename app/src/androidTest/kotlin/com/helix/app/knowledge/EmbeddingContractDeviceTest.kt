package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.account.OfficialServices
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.EmbeddingClient
import com.helix.provider.api.ModelCatalogDiscovery
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelPurpose
import com.helix.provider.api.wire.OkHttpWireClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in, two synthetic inputs only; credentials remain owned by AccountSession. */
class EmbeddingContractDeviceTest {
    @Test fun existingAccountSyntheticBatchContract(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.embeddingProbe")
            org.junit.Assume.assumeTrue("Explicit embedding probe opt-in absent", flag != null)
            require(flag == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            app.account.restore()
            check(app.account.signedIn.value) { "Existing account not signed in" }
            val discovery = BuiltInAccountWire(app.account)
            val directory = ModelCatalogDiscovery.fetch(discovery, BuiltInModel.CATALOG_URL, emptyMap(), true)
            check(directory is ModelCatalogResult.Listed) { "Embedding directory unavailable" }
            val models = directory.models.filter { ModelPurpose.EMBEDDING in directory.purposes[it].orEmpty() }
            check(models.isNotEmpty()) { "No embedding model explicitly listed" }
            val model = models.sorted().first()
            val endpoint = OfficialServices.INFERENCE_BASE_URL + "/embeddings"
            val transport = OkHttpWireClient(followRedirects = false)
            val client = EmbeddingClient(app.account.wire(endpoint, transport), endpoint)
            val synthetic = listOf("Synthetic blue harbor lighthouse.", "Synthetic red mountain observatory.")
            val vectors = client.embed(model, synthetic)
            assertEquals(2, vectors.size)
            assertTrue(vectors.first().isNotEmpty())
            assertEquals(vectors.first().size, vectors.last().size)
            File(app.cacheDir, "embedding-contract-probe.json").writeText(
                buildJsonObject {
                    put("model", model)
                    put("dimension", vectors.first().size)
                    put("batchTwoAccepted", true)
                    put("encoding", "float")
                    put("syntheticOnly", true)
                    put("prefixContract", "not advertised; no prefix assumed by probe")
                    put("maximumBatchAndTokenLimits", "not inferred from a two-input probe")
                }.toString(),
            )
        }
}
