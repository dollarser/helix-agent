package com.helix.app.knowledge

import android.content.Context
import com.helix.app.account.AccountSession
import com.helix.app.account.OfficialServices
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.provider.api.EmbeddingClient
import com.helix.provider.api.ModelPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

internal data class SemanticConfiguration(
    val model: String = "",
    val queryPrefix: String = "",
    val documentPrefix: String = "",
    val modelRevision: String = "",
    val keys: Set<String> = emptySet(),
    val indexGeneration: String = "",
) {
    val fingerprint get() =
        knowledgeHash(
            JsonArray(
                listOf(model, queryPrefix, documentPrefix, modelRevision, indexGeneration).map(::JsonPrimitive),
            ).toString(),
        )
}

/** Explicit selected-document egress consent. No automatic corpus discovery, hidden upload or source authority. */
@Suppress("TooManyFunctions") // One owner binds consent, cache publication and cancellation across indexing/search.
internal class KnowledgeSemanticService(
    context: Context,
    private val store: KnowledgeStore,
    private val account: AccountSession?,
    storageName: String = "knowledge-semantic",
    private val transport: com.helix.provider.api.wire.WireClient =
        com.helix.provider.api.wire
            .OkHttpWireClient(followRedirects = false),
) {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val cache = SemanticIndex(File(context.cacheDir, "$storageName-vectors"))
    private val app = context.applicationContext as? com.helix.app.HelixApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val capacity = kotlinx.coroutines.sync.Semaphore(2)
    private val requests =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Job>()
    private var indexing: Job? = null
    val status = MutableStateFlow("OFF")
    val enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val changes = MutableStateFlow(0L)

    init {
        require(storageName.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        account?.let { current -> scope.launch { current.identityState.collect { if (enabled.value) rebuild() } } }
        scope.launch {
            combine(store.items, store.notes) { _, _ -> Unit }.collect {
                if (enabled.value) rebuild()
            }
        }
    }

    fun configuration() =
        SemanticConfiguration(
            prefs.getString("model", "").orEmpty(),
            prefs.getString("query-prefix", "").orEmpty(),
            prefs.getString("document-prefix", "").orEmpty(),
            prefs.getString("model-revision", "").orEmpty(),
            prefs.getStringSet("documents", emptySet()).orEmpty().toSet(),
            prefs.getString("generation", "").orEmpty(),
        )

    suspend fun models(force: Boolean = false): List<String> =
        withContext(Dispatchers.IO) {
            val service = requireNotNull(app).awaitAppContainer().providerService
            service.builtInModels.refresh(force)
            val catalog = service.builtInModels.current()
            check(catalog.failure == null) { "Embedding directory unavailable" }
            catalog.models.filter { ModelPurpose.EMBEDDING in catalog.purposes[it].orEmpty() }
        }

    @android.annotation.SuppressLint("UseKtx") // Consent changes require checked durable publication.
    suspend fun apply(config: SemanticConfiguration) =
        withContext(Dispatchers.IO) {
            val owner = requireNotNull(account).identity
            require(owner != "signed-out")
            require(config.model in models() && config.keys.isNotEmpty() && config.keys.size <= 64)
            require(
                config.queryPrefix.length <= 512 && config.documentPrefix.length <= 512 &&
                    config.modelRevision.length <= 200,
            )
            require(config.keys.all { it.matches(Regex("(source|note):[A-Za-z0-9_-]{1,100}")) })
            require(account.identity == owner) { "Account changed" }
            enabled.value = false
            indexing?.cancelAndJoin()
            requests.toList().forEach { it.cancelAndJoin() }
            cache.retain(namespace(config), emptySet())
            currentCoroutineContext().ensureActive()
            check(
                prefs
                    .edit()
                    .putString("model", config.model)
                    .putString("query-prefix", config.queryPrefix)
                    .putString("document-prefix", config.documentPrefix)
                    .putString("model-revision", config.modelRevision)
                    .putStringSet("documents", config.keys)
                    .putString("owner", knowledgeHash(owner))
                    .putString(
                        "generation",
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ).putBoolean("enabled", true)
                    .commit(),
            )
            enabled.value = true
            rebuild()
        }

    @android.annotation.SuppressLint("UseKtx")
    fun stop() {
        check(prefs.edit().putBoolean("enabled", false).commit())
        enabled.value = false
        indexing?.cancel()
        requests.forEach { it.cancel() }
        status.value = "OFF"
    }

    suspend fun stopAndWait() {
        stop()
        indexing?.join()
        requests.toList().forEach { it.join() }
    }

    private fun authorized(config: SemanticConfiguration): Boolean =
        enabled.value && prefs.getBoolean("enabled", false) && config.indexGeneration.isNotBlank() &&
            config == configuration() &&
            account?.identity?.let { it != "signed-out" && knowledgeHash(it) == prefs.getString("owner", "") } == true

    private fun namespace(config: SemanticConfiguration) =
        knowledgeHash(
            OfficialServices.INFERENCE_BASE_URL + "|" + prefs.getString("owner", "") + "|" + config.fingerprint,
        )

    fun rebuild() {
        indexing?.cancel()
        indexing =
            scope.launch {
                gate.withLock {
                    val config = configuration()
                    if (!authorized(config)) {
                        status.value = "OFF"
                        return@withLock
                    }
                    try {
                        status.value = "INDEXING"
                        require(config.model in models())
                        val documents = config.keys.mapNotNull { current(it) }
                        cache.retain(namespace(config), documents.map { it.key }.toSet())
                        for (document in documents) {
                            currentCoroutineContext().ensureActive()
                            if (cache.read(namespace(config), document) != null) continue
                            require(authorized(config))
                            index(config, document)
                        }
                        if (authorized(config)) {
                            status.value =
                                if (documents.size == config.keys.size) "READY" else "PARTIAL"
                            changes.value++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IOException) {
                        status.value = "ERROR"
                    } catch (_: IllegalArgumentException) {
                        status.value = "ERROR"
                    } catch (_: IllegalStateException) {
                        status.value = "ERROR"
                    }
                }
            }
    }

    private suspend fun index(
        config: SemanticConfiguration,
        document: SemanticDocument,
    ) {
        val chunks = mutableListOf<SemanticChunk>()
        for (batch in SemanticIndex.ranges(document.text).chunked(2)) {
            require(authorized(config))
            val vectors =
                embed(
                    config,
                    batch.map { (from, to) ->
                        config.documentPrefix +
                            SemanticIndex.text(document, from, to)
                    },
                )
            require(vectors.all { chunks.isEmpty() || it.size == chunks.first().vector.size })
            batch.forEachIndexed { index, (start, end) -> chunks += SemanticChunk(start, end, vectors[index]) }
        }
        currentCoroutineContext().ensureActive()
        require(
            authorized(config) && current(document.key)?.revision == document.revision,
        ) { "Source changed during indexing" }
        cache.write(namespace(config), document, chunks)
    }

    private fun client(): EmbeddingClient {
        val endpoint = OfficialServices.INFERENCE_BASE_URL + "/embeddings"
        return EmbeddingClient(requireNotNull(account).wire(endpoint, transport), endpoint)
    }

    private suspend fun embed(
        config: SemanticConfiguration,
        inputs: List<String>,
    ): List<FloatArray> =
        coroutineScope {
            val request =
                async {
                    capacity.withPermit {
                        require(authorized(config)) { "Semantic consent changed" }
                        client().embed(config.model, inputs)
                    }
                }
            requests.add(request)
            try {
                request.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                throw IllegalArgumentException("Semantic consent changed")
            } finally {
                requests.remove(request)
            }
        }

    suspend fun current(key: String): SemanticDocument? {
        val id = key.substringAfter(':')
        val document =
            if (key.startsWith("source:")) {
                store.get(id)?.takeIf { it.state !in setOf("DELETING", "IMPORTING") }?.let {
                    semanticSourceDocument(it)
                }
            } else {
                store.note(id)?.let { SemanticDocument(key, knowledgeHash(it.body), it.title, it.body) }
            }
        return document?.takeIf {
            it.text.isNotBlank() && it.text.length <= 200_000 &&
                com.helix.app.application.security.ForbiddenContentGuard
                    .reasonFor(it.text) == null
        }
    }

    suspend fun search(
        query: String,
        references: List<KnowledgeReference>? = null,
    ): List<SemanticHit> =
        withContext(Dispatchers.IO) {
            val config = configuration()
            if (!authorized(config) || query.isBlank()) return@withContext emptyList()
            val allowed =
                references?.associate {
                    (if (it.kind == "SOURCE") "source:" else "note:") + it.resourceId to
                        it.sourceHash
                }
            val eligible =
                config.keys.filter { allowed == null || it in allowed }.mapNotNull { key ->
                    current(key)?.takeIf { allowed == null || allowed[key] == it.revision }
                }
            val documents = eligible.mapNotNull { doc -> cache.read(namespace(config), doc)?.let { doc to it } }
            if (documents.size < eligible.size) status.value = "LEXICAL_FALLBACK"
            if (documents.isEmpty()) return@withContext emptyList()
            val dimensions =
                documents
                    .map {
                        it.second
                            .first()
                            .vector.size
                    }.toSet()
            val vector = queryVector(config, query, dimensions)
            if (!authorized(config)) return@withContext emptyList()
            SemanticIndex
                .rank(
                    query,
                    vector,
                    documents,
                ).filter { current(it.document.key)?.revision == it.document.revision }
        }

    private suspend fun queryVector(
        config: SemanticConfiguration,
        query: String,
        dimensions: Set<Int>,
    ): FloatArray? =
        try {
            require(config.model in models())
            embed(config, listOf(config.queryPrefix + query)).single().also { vector ->
                require(dimensions.all { it == vector.size }) { "Embedding dimension changed; rebuild index" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            fallback(config)
        } catch (_: IllegalArgumentException) {
            fallback(config)
        } catch (_: IllegalStateException) {
            fallback(config)
        }

    private fun fallback(config: SemanticConfiguration): FloatArray? {
        if (authorized(config)) status.value = "LEXICAL_FALLBACK"
        return null
    }

    fun close() {
        requests.forEach { it.cancel() }
        scope.cancel()
    }
}
