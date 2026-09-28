package com.helix.app.provider

import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.Clock
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SecretAlias
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.transportIdentity
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilityProbe
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The provider service (HXA-028): the ONLY production code path that builds
 * model providers and runs network operations against them. The UI dispatches
 * intents here and observes the [rows] StateFlow — it holds no network Job
 * (doc 02 section 12; HXA-028 task text).
 *
 * Persistence (all through [HelixStorage] repositories — the UI never touches
 * DAOs):
 * - the provider row: `provider_configs` (alias-only credential, HXA-020);
 * - the secret: `SecretStore` (Android Keystore, put only when the user typed
 *   a key — never into Room/logs/SavedStateHandle, NFR-007);
 * - the connection-test outcome: [ProviderTestStatusStore] (app state);
 * - the cleartext host:port bindings: [CleartextBindingStore] (app state),
 *   created only by the user's explicit risk confirmation and pruned to the
 *   host:ports still referenced by persisted providers (revocable, never
 *   global).
 *
 * One class owns the whole provider surface (rows, create/edit/delete, the
 * connection test, the send-path gates) so the invariants ("no untested
 * provider is selectable", "bindings prune with the endpoints") stay together.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class ProviderService(
    private val storage: HelixStorage,
    private val factory: ProviderFactory,
    private val bindings: CleartextBindingStore,
    private val testStatus: ProviderTestStatusStore,
    private val probe: CapabilityProbe = CapabilityProbe(),
    private val clock: Clock = SystemClock(),
    private val idGenerator: () -> String,
    private val managed: ManagedProviderHooks = ManagedProviderHooks(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val contextSettingsStore: ProviderContextSettingsStore =
        ProviderContextSettingsStore(
            com.helix.app.internal
                .InMemoryLineStore(),
        ),
    val localModels: com.helix.app.localmodel.LocalModelService? = null,
) {
    private val _contextRevision = MutableStateFlow(0L)
    val contextRevision: StateFlow<Long> = _contextRevision.asStateFlow()

    suspend fun contextSettings(
        providerId: String,
        model: String? = null,
    ): ProviderContextSettings {
        val config = storedConfig(providerId)
        val selectedModel = model ?: config.model
        val stored = contextSettingsStore.read(providerId, config.transport.cacheKey, selectedModel)
        val detected = metadataFor(providerId, selectedModel)?.contextWindow
        return stored.withDetectedWindow(detected)
    }

    suspend fun saveContextSettings(
        providerId: String,
        model: String,
        settings: ProviderContextSettings,
    ) {
        val config = storedConfig(providerId)
        val local = config.transport is com.helix.core.model.ProviderTransport.OnDeviceLocal
        if (local) {
            require(settings.manualWindow == null || settings.manualWindow in 1024L..32768L)
            testStatus.modelMetadata.write(config.id, config.transport.cacheKey, emptyMap())
        }
        val previous = contextSettingsStore.read(providerId, config.transport.cacheKey, model)
        contextSettingsStore.write(
            providerId,
            config.transport.cacheKey,
            model,
            if (local) {
                settings.copy(serverWindow = if (settings.manualWindow == null) 4096L else null)
            } else {
                settings.copy(serverWindow = previous.serverWindow)
            },
        )
        _contextRevision.value++
        refresh()
    }

    suspend fun discoverContextWindow(
        providerId: String,
        model: String,
    ): ProviderContextSettings =
        withContext(workScope.coroutineContext) {
            val config = storedConfig(providerId)
            val previous = contextSettingsStore.read(providerId, config.transport.cacheKey, model)
            val detected = factory.create(config).contextWindow(model)
            val updated = previous.copy(serverWindow = detected)
            contextSettingsStore.write(providerId, config.transport.cacheKey, model, updated)
            _contextRevision.value++
            updated
        }

    private val workScope = scope
    private val _rows = MutableStateFlow<List<ProviderRowUi>>(emptyList())

    /** The provider list as persisted state + recorded test statuses. */
    val rows: StateFlow<List<ProviderRowUi>> = _rows.asStateFlow()

    /**
     * Network-operation counter (connect/list/stream calls entered the wire).
     * Exposed for the NFR-011 side-effect assertions: the instrumented tests
     * verify this number is unchanged across the Standard/Advanced switch.
     */
    private val _networkOperations = MutableStateFlow(0)
    val networkOperations: StateFlow<Int> = _networkOperations.asStateFlow()

    private val connectionProbe =
        ProviderConnectionProbe(
            storage,
            factory,
            testStatus,
            probe,
            clock,
            managed,
            ::storedConfig,
            { providerId, model, detected ->
                val config = storedConfig(providerId)
                val previous = contextSettingsStore.read(providerId, config.transport.cacheKey, model)
                contextSettingsStore.write(
                    providerId,
                    config.transport.cacheKey,
                    model,
                    previous.copy(serverWindow = detected),
                )
                _contextRevision.value++
            },
            { _networkOperations.value += 1 },
        )

    /**
     * Re-reads persisted state into [rows] (call on app start and after
     * mutations). Thread-safe: the Room read runs on this service's IO scope,
     * never on the caller's (UI) thread.
     */
    fun refresh() {
        workScope.launch { refreshNow() }
    }

    /** The actual read; only ever run on the service's IO scope. */
    @Suppress("SwallowedException") // corrupt row: the conservative empty fallback IS the handling
    private fun refreshNow() {
        _rows.value =
            try {
                storage.providerConfigs.list().map { entity -> rowUi(entity) }
            } catch (e: IllegalArgumentException) {
                // A corrupt provider row fails closed: it is not shown (it
                // cannot be selected for chat), and the provider screen shows
                // the recoverable list of the other rows.
                emptyList()
            }
    }

    /** One persisted provider as its UI row (a corrupt row throws IAE, fail-closed). */
    private fun rowUi(entity: ProviderConfigEntity): ProviderRowUi =
        providerRowUi(entity, statusFor(entity.id)).copy(
            modelMetadata = testStatus.modelMetadata.read(entity.id, entity.transportIdentity),
            assetSizeBytes = localModels?.assetSize(entity.model),
        )

    /**
     * Persists a new provider from a composed [ProviderDraft].
     *
     * Rules (fail-closed, user-visible errors):
     * - a credential-required provider without a key is refused (FR-LLM-001);
     * - a cleartext (http) provider is saved only when [cleartextConfirmed] —
     *   the UI's explicit per-host:port risk confirmation (doc 10 section 2.5);
     * - the typed key is stored in the Keystore under a fresh alias; the row
     *   stores the alias only (NFR-007).
     *
     * Returns the new provider id. The Room/Keystore work runs on this
     * service's IO scope (main-thread-safe for the UI to call).
     */
    suspend fun create(
        draft: ProviderDraft,
        apiKey: String?,
        cleartextConfirmed: Boolean,
    ): String =
        withContext(workScope.coroutineContext) {
            require(draft.cleartext == null || cleartextConfirmed) {
                "cleartext http to ${draft.endpoint.origin} requires the explicit per-host:port confirmation"
            }
            require(!draft.credentialRequired || !apiKey.isNullOrBlank()) {
                "this provider requires an API key"
            }
            val id = idGenerator()
            val alias =
                if (apiKey.isNullOrBlank()) {
                    null
                } else {
                    val generated = idGenerator()
                    storage.secrets.put(SecretAlias(generated), apiKey)
                    generated
                }
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = id,
                    displayName = draft.displayName,
                    protocol = draft.protocol,
                    endpoint = draft.endpoint.full,
                    model = draft.model,
                    headersJson = draft.headersJson,
                    secretAlias = alias,
                    authKind = if (alias == null) "NONE" else "SECRET",
                    capabilitySnapshot = UNTESTED_SNAPSHOT,
                ),
            )
            draft.cleartext?.let { bindings.authorize(it) }
            testStatus.clear(id)
            refreshNow()
            id
        }

    /**
     * Edits an existing provider (endpoint/model/key). The cleartext rule
     * re-applies to the NEW endpoint (a re-point to a different host:port is a
     * new authorization: ADR-0005 "新 origin … 使旧授权失效"); the old
     * host:port binding is pruned when no longer referenced.
     */
    suspend fun update(
        providerId: String,
        draft: ProviderDraft,
        apiKey: String?,
        cleartextConfirmed: Boolean,
    ) {
        withContext(workScope.coroutineContext) {
            require(!managed.isManaged(providerId)) { "managed provider cannot be edited" }
            require(draft.cleartext == null || cleartextConfirmed) {
                "cleartext http to ${draft.endpoint.origin} requires the explicit per-host:port confirmation"
            }
            val existing = storage.providerConfigs.resolve(providerId)
            require(existing.provisioningKind == "USER_CONFIGURED") { "Provider is not user-configured" }
            val alias =
                when {
                    !draft.credentialRequired -> {
                        if (existing.secretAlias != null && existing.secretAlias != ProviderFactory.NO_KEY_ALIAS) {
                            storage.secrets.delete(SecretAlias(requireNotNull(existing.secretAlias)))
                        }
                        null
                    }

                    apiKey.isNullOrBlank() -> {
                        existing.secretAlias
                    }

                    // keep the stored key
                    else -> {
                        val updatedAlias = existing.secretAlias ?: idGenerator()
                        storage.secrets.put(SecretAlias(updatedAlias), apiKey)
                        updatedAlias
                    }
                }
            storage.providerConfigs.overwrite(
                ProviderConfigSpec(
                    id = providerId,
                    displayName = draft.displayName,
                    protocol = draft.protocol,
                    endpoint = draft.endpoint.full,
                    model = draft.model,
                    headersJson = draft.headersJson,
                    secretAlias = alias,
                    authKind = if (alias == null) "NONE" else "SECRET",
                    capabilitySnapshot = UNTESTED_SNAPSHOT,
                ),
            )
            // Editing invalidates the previous test result (new endpoint/model):
            // the provider must be re-tested before it is selectable again.
            testStatus.clear(providerId)
            draft.cleartext?.let { bindings.authorize(it) }
            pruneBindingsToPersistedEndpoints()
            refreshNow()
        }
    }

    /** Deletes the provider (sessions keep their rows, providerId nulled by the FK). */
    suspend fun delete(providerId: String) {
        withContext(workScope.coroutineContext) {
            require(!managed.isManaged(providerId)) { "managed provider cannot be deleted" }
            val entity = storage.providerConfigs.resolve(providerId)
            if (entity.secretAlias != null && entity.secretAlias != ProviderFactory.NO_KEY_ALIAS) {
                storage.secrets.delete(SecretAlias(requireNotNull(entity.secretAlias)))
            }
            if (entity.provisioningKind == "ON_DEVICE_ASSET") localModels?.delete(entity.model)
            storage.providerConfigs.delete(providerId)
            testStatus.clear(providerId)
            pruneBindingsToPersistedEndpoints()
            refreshNow()
        }
    }

    /** Catalog discovery plus one short text reply; independent of optional capability detection. */
    suspend fun runConnectionTest(providerId: String): ProbeOutcome =
        withContext(workScope.coroutineContext) {
            connectionProbe.run(providerId).also { refreshNow() }
        }

    /** Explicit capability detection; failures never invalidate a passed connection. */
    suspend fun runCapabilityTest(providerId: String): ProbeOutcome =
        withContext(workScope.coroutineContext) {
            connectionProbe.run(providerId, detectCapabilities = true).also { refreshNow() }
        }

    suspend fun installCuratedLocalModel(
        entryId: String,
        source: com.helix.app.localmodel.LocalModelCatalogSource,
        onProgress: (com.helix.app.localmodel.LocalModelTransferProgress) -> Unit = {},
    ): com.helix.app.localmodel.LocalModelInstallResult =
        installLocalModel(onProgress) { models, progress -> models.downloadCatalog(entryId, source, progress) }

    suspend fun installManualLocalModel(
        url: String,
        hash: String,
        size: Long,
        name: String,
        onProgress: (com.helix.app.localmodel.LocalModelTransferProgress) -> Unit = {},
    ): com.helix.app.localmodel.LocalModelInstallResult =
        installLocalModel(onProgress) { models, progress -> models.download(url, hash, size, name, progress) }

    /** AndroidTest-only cleartext seam; completion still uses production install/probe orchestration. */
    internal suspend fun installLocalModelForTest(
        url: String,
        hash: String,
        size: Long,
        name: String,
        onProgress: (com.helix.app.localmodel.LocalModelTransferProgress) -> Unit = {},
    ): com.helix.app.localmodel.LocalModelInstallResult =
        installLocalModel(onProgress) { models, progress ->
            models.downloadForTest(url, hash, size, name, progress)
        }

    private suspend fun installLocalModel(
        onProgress: (com.helix.app.localmodel.LocalModelTransferProgress) -> Unit,
        transfer: suspend (
            com.helix.app.localmodel.LocalModelService,
            (com.helix.app.localmodel.LocalModelTransferProgress) -> Unit,
        ) -> String,
    ): com.helix.app.localmodel.LocalModelInstallResult =
        withContext(workScope.coroutineContext.minusKey(kotlinx.coroutines.Job)) {
            val models = requireNotNull(localModels) { "On-device models are unavailable" }
            val modelId = transfer(models, onProgress)
            refreshNow()
            val connection = connectionProbe.run(modelId)
            val capabilities =
                if (connection is ProbeOutcome.Ok) {
                    connectionProbe.run(modelId, detectCapabilities = true)
                } else {
                    null
                }
            refreshNow()
            com.helix.app.localmodel
                .LocalModelInstallResult(modelId, modelId, connection, capabilities)
        }

    /**
     * The typed config of a persisted provider (fail-closed on corruption).
     * Runs on the service's IO scope (Room read).
     */
    suspend fun storedConfig(providerId: String): ProviderConfig =
        withContext(workScope.coroutineContext) {
            configFrom(storage.providerConfigs.resolve(providerId))
        }

    suspend fun openManagedAccount(providerId: String): ManagedProviderAccountResult =
        withContext(workScope.coroutineContext) {
            if (!managed.isManaged(providerId)) {
                ManagedProviderAccountResult.NOT_SUPPORTED
            } else {
                managed.openAccount(providerId)
            }
        }

    /** Decodes one persisted row into its typed config (throws IAE on corruption). */
    private fun configFrom(e: ProviderConfigEntity): ProviderConfig =
        ProviderConfig.fromStorage(
            e.id,
            e.displayName,
            e.protocol,
            e.endpoint,
            e.model,
            e.headersJson,
            e.secretAlias,
            e.capabilitySnapshot,
            e.provisioningKind,
            e.transportKind,
            e.authKind,
        )

    /**
     * The send-path cleartext gate (doc 10 section 2.5; HXA-027 boundary):
     * https is always permitted; http requires the user-confirmed binding for
     * the exact host:port. The UI calls this before dispatching a send; a
     * false result is a user-visible block, never a silent attempt.
     */
    suspend fun isCleartextPermitted(providerId: String): Boolean {
        val config = storedConfig(providerId)
        val network = config.transport as? ProviderTransport.Network ?: return true
        return CleartextAuthorization.isPermitted(network.endpoint, bindings.all())
    }

    /**
     * The model provider for a persisted config (chat service entry point).
     * Runs on the service's IO scope (Room read).
     */
    suspend fun modelProviderFor(providerId: String): ModelProvider {
        val config = storedConfig(providerId)
        if (config.transport is ProviderTransport.Network) _networkOperations.value += 1
        return factory.create(config)
    }

    /** The test status of one provider (UI rows are rebuilt from this). */
    fun statusFor(providerId: String): ConnectionTestStatus = testStatus.statusFor(providerId)

    /** True when the provider passed its connection test (chat-selectable). */
    fun chatSelectable(providerId: String): Boolean = statusFor(providerId) is ConnectionTestStatus.Passed

    /**
     * The egress-disclosure target for one provider (doc 10 section 2.6): the
     * display + residence facts the pre-send gate shows. Derived from the
     * persisted endpoint only — never from the template name.
     */
    suspend fun egressTargetFor(providerId: String): EgressDisclosure.EgressTarget {
        val config = storedConfig(providerId)
        return EgressDisclosure.EgressTarget(
            providerId = config.id,
            providerName = config.displayName,
            protocol = (config.transport as? ProviderTransport.Network)?.protocol,
            origin = (config.transport as? ProviderTransport.Network)?.endpoint?.origin.orEmpty(),
            residence = config.residence(),
        )
    }

    /**
     * Revokes every cleartext binding no longer referenced by a persisted
     * provider. A row with an unparseable endpoint is skipped (its binding,
     * if any, is revoked — fail closed).
     */
    @Suppress("SwallowedException") // unparseable endpoint: skipping the row IS the fail-closed handling
    private fun pruneBindingsToPersistedEndpoints() {
        val referenced =
            storage.providerConfigs
                .list()
                .mapNotNull { entity ->
                    val endpoint =
                        try {
                            NormalizedEndpoint.parse(entity.endpoint ?: return@mapNotNull null)
                        } catch (e: IllegalArgumentException) {
                            return@mapNotNull null
                        }
                    CleartextAuthorization.requiredFor(endpoint)
                }.toSet()
        bindings.pruneTo(referenced)
    }

    /**
     * The parsed capability snapshot of one persisted provider (HXA-055): [ProviderCapabilities]
     * or null when the stored snapshot is missing/corrupt — a NULL is read by the caller as
     * "no confirmed capability" (fail closed: the send path blocks, the probe re-establishes).
     */
    @Suppress("SwallowedException") // an unparseable snapshot IS the null outcome (fail closed)
    suspend fun capabilitiesFor(providerId: String, model: String? = null): ProviderCapabilities? =
        runCatching {
            if (model == null) {
                ProviderCapabilities.parse(storage.providerConfigs.resolve(providerId).capabilitySnapshot)
            } else {
                withContext(workScope.coroutineContext) {
                    val entity = storage.providerConfigs.resolve(providerId)
                    rowUi(entity)
                        .copy(capabilities = ProviderCapabilities.parse(entity.capabilitySnapshot))
                        .capabilitiesForModel(model)
                }
            }
        }.getOrNull()

    fun metadataFor(
        providerId: String,
        model: String,
    ): ModelMetadata? =
        rows.value
            .firstOrNull { it.id == providerId }
            ?.modelMetadata
            ?.get(model)

    fun reasoningOptions(
        providerId: String,
        model: String,
    ): List<ReasoningEffort> {
        val row = rows.value.firstOrNull { it.id == providerId && it.chatSelectable } ?: return emptyList()
        val explicit = row.modelMetadata[model]?.reasoningEfforts
        return when {
            explicit != null && explicit.isNotEmpty() -> listOf(ReasoningEffort.OFF) + explicit
            explicit != null -> emptyList()
            model == row.model && row.capabilities?.reasoning == true -> ReasoningEffort.FALLBACK
            else -> emptyList()
        }
    }

    /**
     * The user-visible manual vision declaration (HXA-0014 §4 / ADR-0014: vision "must come from
     * a real probe or a user-visible manual configuration"): flips [enabled] on the stored
     * snapshot and marks the source [CapabilitySource.MANUAL] so the UI shows 「手动声明」. A
     * re-run connection test replaces the whole snapshot (the probe result wins over the
     * manual mark — the probe is the stronger evidence).
     */
    suspend fun declareVisionCapability(
        providerId: String,
        enabled: Boolean,
    ) {
        require(!managed.isManaged(providerId)) { "managed provider capabilities cannot be overridden" }
        val row = storage.providerConfigs.resolve(providerId)
        val current =
            runCatching { ProviderCapabilities.parse(row.capabilitySnapshot) }.getOrNull()
                ?: ProviderCapabilities.parse(UNTESTED_SNAPSHOT)
        val declared = current.copy(vision = enabled).withManualSource()
        storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                id = row.id,
                displayName = row.displayName,
                protocol = row.protocol?.let(ProviderProtocol::parse),
                endpoint = row.endpoint,
                model = row.model,
                headersJson = row.headersJson,
                secretAlias = row.secretAlias,
                provisioningKind = row.provisioningKind,
                transportKind = row.transportKind,
                authKind = row.authKind,
                capabilitySnapshot = ProviderCapabilities.toJsonString(declared),
            ),
        )
        refresh()
    }

    private companion object {
        /**
         * The conservative all-false MANUAL snapshot stored for a provider that
         * has not completed a connection test: nothing is claimed (doc 10
         * section 2.4: rely on capability tests, never on product names), and
         * the UI derives its "未测试" state from [ProviderTestStatusStore], not
         * from this snapshot.
         */
        val UNTESTED_SNAPSHOT: String =
            ProviderCapabilities.toJsonString(
                ProviderCapabilities(
                    streaming = false,
                    toolCalls = false,
                    parallelToolCalls = false,
                    vision = false,
                    reasoning = false,
                    jsonSchemaOutput = false,
                    maxContextTokens = null,
                    source = CapabilitySource.MANUAL,
                ),
            )
    }
}
