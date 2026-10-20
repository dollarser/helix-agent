package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.Clock
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BuiltInModelDiscoveryTest {
    private val backing = InMemoryLineStore()
    private val store = BuiltInModelCatalogStore(backing) { "fixture-account" }
    private var millis = 1_000L
    private var calls = 0
    private var result: suspend () -> ModelCatalogResult = {
        ModelCatalogResult.Listed(listOf("new-model", BuiltInModel.MODEL_ID))
    }
    private val source =
        object : ModelProvider {
            override val descriptor =
                ProviderDescriptor("fixture", "Fixture", BuiltInModel.connection, BuiltInModel.MODEL_ID)

            override suspend fun listModels(): ModelCatalogResult {
                calls++
                return result()
            }

            override suspend fun validateConfiguration(): ProviderCheckResult = error("No health probe")

            override fun stream(request: ModelRequest): Nothing = error("Directory must never generate")
        }

    private fun discovery() =
        BuiltInModelDiscovery(
            store,
            object : Clock {
                override fun now() = Instant.ofEpochMilli(millis)
            },
            { source },
            {},
        )

    @Test fun signedOutNeverFetchesOrReadsRetiredDirectory() =
        runBlocking {
            val signedOut = BuiltInModelCatalogStore(backing)
            signedOut.save(BuiltInModelCatalog(100, listOf("retired-model")))
            val discovery =
                BuiltInModelDiscovery(
                    signedOut,
                    object : Clock {
                        override fun now() = Instant.ofEpochMilli(millis)
                    },
                    { error("Signed-out discovery must not create a provider") },
                    {},
                )
            discovery.refresh(true)
            assertEquals(emptyList<String>(), discovery.current().models)
        }

    @Test fun directoryRefreshIsDynamicCachedAcrossRestartAndNeverInfers() =
        runBlocking {
            val discovery = discovery()
            discovery.refresh(false)
            assertEquals(listOf("new-model", BuiltInModel.MODEL_ID), discovery.current().models)
            repeat(3) { discovery.refresh(false) }
            discovery().refresh(false)
            assertEquals(1, calls)
            millis += BuiltInModelCatalog.SUCCESS_TTL
            result = { ModelCatalogResult.Listed(listOf("replacement")) }
            discovery.refresh(false)
            assertEquals(listOf("replacement"), discovery.current().models)
            assertEquals(2, calls)
        }

    @Test fun failureWithoutHistoryIsEmptyAndTransientFailureRetainsLastDirectory() =
        runBlocking {
            val discovery = discovery()
            result = { ModelCatalogResult.Failed(ModelErrorCode.AUTH, "not authorized", false) }
            discovery.refresh(false)
            assertEquals(emptyList<String>(), discovery.current().models)
            millis += BuiltInModelCatalog.FAILURE_TTL
            result = { ModelCatalogResult.Listed(listOf("model-a")) }
            discovery.refresh(false)
            millis += 3_000
            result = { ModelCatalogResult.Failed(ModelErrorCode.SERVER_ERROR, "unavailable", false) }
            discovery.refresh(true)
            assertEquals(listOf("model-a"), discovery().current().models)
            assertEquals(ModelErrorCode.SERVER_ERROR, store.read().failure)
            discovery.refresh(false)
            assertEquals(3, calls)
        }

    @Test fun successfulEmptyDirectoryRemovesStaleModelsAndSurvivesRestart() =
        runBlocking {
            val discovery = discovery()
            discovery.refresh(false)
            millis += BuiltInModelCatalog.SUCCESS_TTL
            result = { ModelCatalogResult.Listed(emptyList()) }
            discovery.refresh(false)
            assertEquals(emptyList<String>(), discovery().current().models)
            assertEquals(null, discovery.current().failure)
            discovery().refresh(false)
            assertEquals(2, calls)
        }

    @Test fun concurrentRefreshesCoalesceAndCancellationDoesNotPublishPartialState() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            result = {
                entered.complete(Unit)
                release.await()
                ModelCatalogResult.Listed(listOf("model-a"))
            }
            val discovery = discovery()
            val first = launch { discovery.refresh(true) }
            entered.await()
            val second = launch { discovery.refresh(true) }
            release.complete(Unit)
            first.join()
            second.join()
            assertEquals(1, calls)
            val before = store.read()
            millis += 3_000
            val waiting = CompletableDeferred<Unit>()
            result = {
                waiting.complete(Unit)
                CompletableDeferred<ModelCatalogResult>().await()
            }
            val cancelled = launch { discovery.refresh(true) }
            waiting.await()
            cancelled.cancelAndJoin()
            assertEquals(before, store.read())
        }
}

class BuiltInAccountCatalogIdentityTest {
    @org.junit.Test
    fun accountChangeAndLatePublicationCannotReusePreviousDirectory() {
        var identity = "first-account-session"
        val store =
            BuiltInModelCatalogStore(
                com.helix.app.internal
                    .InMemoryLineStore(),
            ) { identity }
        val first = store.identity()
        store.save(BuiltInModelCatalog(100, listOf("first-account-model")))
        org.junit.Assert.assertEquals(listOf("first-account-model"), store.read().models)
        identity = "signed-out"
        org.junit.Assert.assertTrue(store.read().models.isEmpty())
        org.junit.Assert.assertFalse(store.accountAvailable())
        identity = "second-account-session"
        org.junit.Assert.assertTrue(store.read().models.isEmpty())
        store.save(BuiltInModelCatalog(200, listOf("late-first-account-model")), first)
        org.junit.Assert.assertTrue(store.read().models.isEmpty())
    }
}
