package com.helix.app.provider

import com.helix.core.model.Clock
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilityProbe
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.CredentialLookup
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
@Suppress("TooManyFunctions")
class ProviderService(
    private val storage: HelixStorage,
    private val factory: ProviderFactory,
    private val bindings: CleartextBindingStore,
    private val testStatus: ProviderTestStatusStore,
    private val probe: CapabilityProbe = CapabilityProbe(),
    private val clock: Clock = SystemClock(),
    private val idGenerator: () -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
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
    private fun rowUi(entity: ProviderConfigEntity): ProviderRowUi = providerRowUi(entity, statusFor(entity.id))

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
                    ProviderFactory.NO_KEY_ALIAS
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
            require(draft.cleartext == null || cleartextConfirmed) {
                "cleartext http to ${draft.endpoint.origin} requires the explicit per-host:port confirmation"
            }
            val existing = storage.providerConfigs.resolve(providerId)
            val alias =
                when {
                    !draft.credentialRequired -> {
                        if (existing.secretAlias != ProviderFactory.NO_KEY_ALIAS) {
                            storage.secrets.delete(SecretAlias(existing.secretAlias))
                        }
                        ProviderFactory.NO_KEY_ALIAS
                    }

                    apiKey.isNullOrBlank() -> {
                        existing.secretAlias
                    }

                    // keep the stored key
                    else -> {
                        storage.secrets.put(SecretAlias(existing.secretAlias), apiKey)
                        existing.secretAlias
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
            val entity = storage.providerConfigs.resolve(providerId)
            if (entity.secretAlias != ProviderFactory.NO_KEY_ALIAS) {
                storage.secrets.delete(SecretAlias(entity.secretAlias))
            }
            storage.providerConfigs.delete(providerId)
            testStatus.clear(providerId)
            pruneBindingsToPersistedEndpoints()
            refreshNow()
        }
    }

    /**
     * Runs the five-phase connection test (HXA-025 [CapabilityProbe]) against
     * the persisted provider. On success the PROBED capability snapshot is
     * persisted into the row (it becomes chat-selectable); since HXA-059 the
     * backend model list (phase 2) rides with the PASSED status (null when the
     * backend does not expose a list) so the UI can offer it for selection.
     * On failure the phase + safe error class are recorded (the row stays
     * non-selectable; no list is ever shown for a failed test).
     */
    suspend fun runConnectionTest(providerId: String): ProbeOutcome =
        withContext(workScope.coroutineContext) {
            runConnectionTestNow(providerId)
        }

    /** The probe itself; only ever run on the service's IO scope (network + Room). */
    private suspend fun runConnectionTestNow(providerId: String): ProbeOutcome {
        val config = storedConfig(providerId)
        val provider = factory.create(config)
        _networkOperations.value += 1
        val outcome = probe.probe(provider)
        when (outcome) {
            is ProbeOutcome.Ok -> {
                storage.providerConfigs.overwrite(
                    storage.providerConfigs.resolve(providerId).let { e ->
                        ProviderConfigSpec(
                            id = e.id,
                            displayName = e.displayName,
                            protocol = ProviderProtocol.parse(e.protocol),
                            endpoint = e.endpoint,
                            model = e.model,
                            headersJson = e.headersJson,
                            secretAlias = e.secretAlias,
                            capabilitySnapshot = ProviderCapabilities.toJsonString(outcome.capabilities),
                        )
                    },
                )
                testStatus.recordPassed(
                    providerId,
                    clock.now().toEpochMilli(),
                    outcome.capabilities,
                    outcome.models,
                )
            }

            is ProbeOutcome.Failed -> {
                testStatus.recordFailed(
                    providerId,
                    clock.now().toEpochMilli(),
                    outcome.phase,
                    outcome.code,
                    outcome.retryable,
                )
            }
        }
        refreshNow()
        return outcome
    }

    /**
     * The typed config of a persisted provider (fail-closed on corruption).
     * Runs on the service's IO scope (Room read).
     */
    suspend fun storedConfig(providerId: String): ProviderConfig =
        withContext(workScope.coroutineContext) {
            configFrom(storage.providerConfigs.resolve(providerId))
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
        )

    /**
     * The send-path cleartext gate (doc 10 section 2.5; HXA-027 boundary):
     * https is always permitted; http requires the user-confirmed binding for
     * the exact host:port. The UI calls this before dispatching a send; a
     * false result is a user-visible block, never a silent attempt.
     */
    suspend fun isCleartextPermitted(providerId: String): Boolean {
        val config = storedConfig(providerId)
        return CleartextAuthorization.isPermitted(config.endpoint, bindings.all())
    }

    /**
     * The model provider for a persisted config (chat service entry point).
     * Runs on the service's IO scope (Room read).
     */
    suspend fun modelProviderFor(providerId: String): ModelProvider {
        _networkOperations.value += 1
        return factory.create(storedConfig(providerId))
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
    suspend fun egressTargetFor(providerId: String): com.helix.app.chat.EgressDisclosure.EgressTarget {
        val config = storedConfig(providerId)
        return com.helix.app.chat.EgressDisclosure.EgressTarget(
            providerId = config.id,
            providerName = config.displayName,
            protocol = config.protocol,
            origin = config.endpoint.origin,
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
                            NormalizedEndpoint.parse(entity.endpoint)
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
    suspend fun capabilitiesFor(providerId: String): ProviderCapabilities? =
        runCatching {
            ProviderCapabilities.parse(
                storage.providerConfigs.resolve(providerId).capabilitySnapshot,
            )
        }.getOrNull()

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
        val row = storage.providerConfigs.resolve(providerId)
        val current =
            runCatching { ProviderCapabilities.parse(row.capabilitySnapshot) }.getOrNull()
                ?: ProviderCapabilities.parse(UNTESTED_SNAPSHOT)
        val declared = current.copy(vision = enabled).withManualSource()
        storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                id = row.id,
                displayName = row.displayName,
                protocol = ProviderProtocol.parse(row.protocol),
                endpoint = row.endpoint,
                model = row.model,
                headersJson = row.headersJson,
                secretAlias = row.secretAlias,
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
                    source = com.helix.provider.api.CapabilitySource.MANUAL,
                ),
            )
    }
}
