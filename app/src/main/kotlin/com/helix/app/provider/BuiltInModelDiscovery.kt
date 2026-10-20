package com.helix.app.provider

import com.helix.core.model.Clock
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Single-flight, catalog-only discovery. A listed model is not an inference health guarantee. */
internal class BuiltInModelDiscovery(
    private val store: BuiltInModelCatalogStore,
    private val clock: Clock,
    private val provider: suspend () -> ModelProvider,
    private val onChanged: () -> Unit,
) {
    private val gate = Mutex()
    private val publicationLock = Any()
    private var revision = 0L

    fun current(): BuiltInModelCatalog = synchronized(publicationLock) { store.read() }

    suspend fun refresh(force: Boolean) =
        withContext(Dispatchers.IO) {
            if (!store.accountAvailable()) return@withContext
            val account = store.identity()
            val observed = synchronized(publicationLock) { revision }
            gate.withLock {
                val cached = current()
                if (observed != synchronized(publicationLock) { revision } ||
                    (!force && cached.fresh(now()))
                ) {
                    return@withLock
                }
                if (force && cached.checkedAt > 0 && now() - cached.checkedAt < MANUAL_COOLDOWN) return@withLock
                val result = fetch()
                if (store.identity() != account) return@withLock
                publish(if (result.second == null) result.first else cached.models, result.second, observed, account)
            }
        }

    private suspend fun fetch(): Pair<List<String>, ModelErrorCode?> =
        try {
            withTimeout(CATALOG_TIMEOUT) {
                when (val catalog = provider().listModels()) {
                    is ModelCatalogResult.Listed -> catalog.models.take(MAX_MODELS).distinct() to null
                    is ModelCatalogResult.Failed -> emptyList<String>() to catalog.code
                    ModelCatalogResult.Unsupported -> emptyList<String>() to ModelErrorCode.PROTOCOL
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            emptyList<String>() to ModelErrorCode.TIMEOUT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            emptyList<String>() to ModelErrorCode.PROTOCOL
        } catch (_: java.io.IOException) {
            emptyList<String>() to ModelErrorCode.TRANSPORT
        }

    private fun publish(
        models: List<String>,
        failure: ModelErrorCode?,
        expectedRevision: Long,
        expectedAccount: List<String>,
    ) {
        synchronized(publicationLock) {
            if (revision != expectedRevision) return
            store.save(BuiltInModelCatalog(now(), models, failure), expectedAccount)
            revision++
        }
        onChanged()
    }

    private fun now() = clock.now().toEpochMilli()

    private companion object {
        const val MAX_MODELS = 128
        const val CATALOG_TIMEOUT = 10_000L
        const val MANUAL_COOLDOWN = 3_000L
    }
}
