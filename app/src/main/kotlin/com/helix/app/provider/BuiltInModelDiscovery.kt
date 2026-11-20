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

    suspend fun contextWindow(
        model: String,
        force: Boolean = false,
    ): Long? {
        val cached = current()
        val retryMissing =
            cached.contextWindows[model] == null &&
                now() - cached.checkedAt >= BuiltInModelCatalog.FAILURE_TTL
        refresh(force || retryMissing)
        return current().contextWindows[model]
    }

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
                if (force && cached.checkedAt > 0 &&
                    now() - cached.checkedAt in 0 until MANUAL_COOLDOWN
                ) {
                    return@withLock
                }
                val result = fetch()
                if (store.identity() != account) return@withLock
                publish(result.first, result.second, observed, account)
            }
        }

    private suspend fun fetch(): Pair<com.helix.provider.api.ModelCatalogResult.Listed?, ModelErrorCode?> =
        try {
            withTimeout(CATALOG_TIMEOUT) {
                when (val catalog = provider().listModels()) {
                    is ModelCatalogResult.Listed -> {
                        catalog.copy(
                            models = catalog.models.take(MAX_MODELS),
                            purposes =
                                catalog.purposes.filterKeys {
                                    it in
                                        catalog.models.take(MAX_MODELS)
                                },
                        ) to
                            null
                    }

                    is ModelCatalogResult.Failed -> {
                        null to catalog.code
                    }

                    ModelCatalogResult.Unsupported -> {
                        null to ModelErrorCode.PROTOCOL
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            null to ModelErrorCode.TIMEOUT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            null to ModelErrorCode.PROTOCOL
        } catch (_: java.io.IOException) {
            null to ModelErrorCode.TRANSPORT
        }

    private fun publish(
        catalog: com.helix.provider.api.ModelCatalogResult.Listed?,
        failure: ModelErrorCode?,
        expectedRevision: Long,
        expectedAccount: List<String>,
    ) {
        synchronized(publicationLock) {
            if (revision != expectedRevision) return
            if (store.identity() != expectedAccount) return
            val previous = store.read()
            val next =
                if (catalog == null) {
                    previous.copy(checkedAt = now(), failure = requireNotNull(failure))
                } else {
                    mergeCatalog(previous, catalog)
                }
            store.save(next, expectedAccount)
            revision++
        }
        onChanged()
    }

    private fun now() = clock.now().toEpochMilli()

    private fun mergeCatalog(
        previous: BuiltInModelCatalog,
        catalog: ModelCatalogResult.Listed,
    ): BuiltInModelCatalog {
        val complete =
            if (!catalog.partial) {
                catalog.models
            } else {
                previous.completeModels?.let { (it + catalog.models).distinct() }
            }
        val tasks =
            catalog.purposes +
                if (catalog.partial) previous.purposes.filterKeys { it !in catalog.models } else emptyMap()
        val windows =
            catalog.contextWindows +
                if (catalog.partial) {
                    previous.contextWindows.filterKeys { it !in catalog.models }
                } else {
                    emptyMap()
                }
        if ((complete?.size ?: 0) > MAX_MODELS || tasks.size > MAX_MODELS || windows.size > MAX_MODELS) {
            return previous.copy(checkedAt = now(), failure = ModelErrorCode.PROTOCOL)
        }
        return BuiltInModelCatalog(
            checkedAt = now(),
            models = catalog.models,
            purposes = tasks,
            partial = catalog.partial,
            successfulAt = now(),
            completeModels = complete,
            contextWindows = windows,
        )
    }

    private companion object {
        const val MAX_MODELS = 1024
        const val CATALOG_TIMEOUT = 10_000L
        const val MANUAL_COOLDOWN = 3_000L
    }
}
