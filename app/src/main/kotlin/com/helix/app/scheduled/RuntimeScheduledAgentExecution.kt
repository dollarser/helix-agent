package com.helix.app.scheduled

import com.helix.app.APP_SCOPE_ID
import com.helix.app.chat.ChatService
import com.helix.app.engine.TurnEngine
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTurnSnapshot
import com.helix.app.provider.analysisHash
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A host adapter over the production Runtime. All tools retain schema/policy/approval/limits/audit. */
class RuntimeScheduledAgentExecution(
    private val storage: HelixStorage,
    private val providers: ProviderService,
    private val chat: ChatService,
    private val engine: TurnEngine,
    private val tools: ToolPipeline,
    private val ledger: com.helix.core.storage.scheduled.ScheduledAgentStore,
) : ScheduledAgentExecution {
    init {
        chat.installScheduledModelGuard(::verifyModelRequest)
    }

    override suspend fun isBusy(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            engine.sessionBlocker(sessionId) != null || engine.liveExecution.hasActive(sessionId)
        }

    override suspend fun sessions(): List<ScheduledSessionChoice> =
        withContext(Dispatchers.IO) {
            storage.sessions
                .list()
                .filter { it.archivedAt == null && it.providerId != null }
                .take(200)
                .map { ScheduledSessionChoice(it.id, it.title) }
        }

    @Suppress("LongMethod") // One immutable authorization snapshot; no model execution.
    override suspend fun scope(sessionId: String, maxRuntimeMillis: Long): ScheduledAgentScope =
        withContext(Dispatchers.IO) {
            require(maxRuntimeMillis > 0)
            val session = storage.sessions.resolve(sessionId)
            check(session.archivedAt == null)
            val providerId = requireNotNull(session.providerId)
            val config = providers.storedConfig(providerId)
            require(config.provisioning != ProviderProvisioningKind.MANAGED_ACCOUNT)
            val model = session.modelId ?: config.model
            require(
                providers.rows.value
                    .first { it.id == providerId }
                    .modelSelectable(model),
            )
            val target = providers.egressTargetFor(providerId)
            val binding = requireNotNull(storage.workspaces.binding(sessionId))
            val workspace = requireNotNull(storage.workspaces.find(binding.workspaceId))
            check(workspace.availability == "READY")
            val permission =
                storage.sessionPermissionConfigs.forSession(sessionId) ?: storage.sessionPermissionConfigs.appDefault()
            val catalog = JSONArray()
            val bindings = JSONArray()
            tools.registry.snapshot().sortedWith(compareBy({ it.ref.name.value }, { it.ref.owner })).forEach { tool ->
                val ref = tool.ref
                bindings.put(bindingIdentity(ref))
                val availability =
                    storage.toolAvailability.statesFor(
                        tool.descriptor.origin.canonicalOf(),
                        ref.name.value,
                        sessionId,
                        session.directoryRef ?: APP_SCOPE_ID,
                    )
                // Incarnation is process-local, never part of a durable approval/configuration identity.
                catalog.put(
                    JSONObject()
                        .put("name", ref.name.value)
                        .put("version", ref.version.value)
                        .put(
                            "owner",
                            ref.owner,
                        ).put("contract", ref.contractHash)
                        .put("implementation", ref.implementationRevision)
                        .put("availability", availability.toString()),
                )
            }
            val facts =
                JSONObject()
                    .put("session", sessionId)
                    .put("provider", ProviderTurnSnapshot.encode(config, model))
                    .put("config", analysisHash(config.toString()))
                    .put("binding", binding.toString())
                    .put("workspace", workspace.toString())
                    .put("permission", permission.toString())
                    .put("tools", catalog)
                    .put(
                        "mode",
                        "PLAN",
                    ).put("outputTokens", OUTPUT_TOKENS)
                    .put("deadlineMillis", maxRuntimeMillis)
            ScheduledAgentScope(
                sessionId,
                session.title,
                providerId,
                target.providerName,
                model,
                target.origin,
                target.residence.name,
                session.directoryRef.orEmpty(),
                catalog.length(),
                analysisHash(facts.toString()),
                analysisHash(config.connection.toString()),
                workspaceIdentity(binding.workspaceId, binding.relativePath, binding.revision),
                bindings.toString(),
                maxRuntimeMillis,
            )
        }

    /** Every request, including compaction, is checked against the durable occurrence authorization. */
    internal suspend fun verifyModelRequest(
        turnId: String,
        callId: String,
        provider: com.helix.provider.api.ProviderDescriptor,
        request: com.helix.core.model.ModelRequest,
    ) = withContext(Dispatchers.IO) {
        val turn = storage.turns.resolve(turnId)
        val client = turn.clientRequestId ?: return@withContext
        if (!client.startsWith(REQUEST_PREFIX)) return@withContext
        val attempt = requireNotNull(ledger.dao.attempt(client.removePrefix(REQUEST_PREFIX)))
        val occurrence = requireNotNull(ledger.dao.occurrence(attempt.ruleId, attempt.revision, attempt.dueAt))
        val rule = ScheduledRuleSnapshot.decode(occurrence.ruleJson)
        val bound = ScheduledAgentScope.decode(rule.scopeJson)
        check(rule.id == attempt.ruleId && rule.revision == attempt.revision)
        check(rule.creator == "LOCAL_USER" && turn.sessionId == attempt.sessionId && rule.sessionId == turn.sessionId)
        check(System.currentTimeMillis() - attempt.createdAt < bound.maxRuntimeMillis)
        check(attempt.state in setOf("ADMITTING", "RUNNING", "WAITING"))
        check(scope(rule.sessionId, bound.maxRuntimeMillis).hash == rule.scopeHash && bound.hash == rule.scopeHash)
        check(provider.id == bound.providerId && request.model == bound.model)
        check(analysisHash(provider.connection.toString()) == bound.connectionHash)
        val runtime = requireNotNull(storage.turnRuntimeRecords.find(turnId))
        check(runtime.mode == "PLAN")
        val binding = requireNotNull(storage.workspaces.requestBinding(callId))
        check(
            workspaceIdentity(binding.workspaceId, binding.relativePath, binding.bindingRevision) ==
                bound.workspaceBinding,
        )
        val allowed =
            JSONArray(bound.bindings).let { refs ->
                (0 until refs.length()).map { refs.getString(it) }.toSet()
            }
        check(request.tools.all { it.bindingRef?.let(::bindingIdentity) in allowed })
        check(request.maxOutputTokens?.let { it <= OUTPUT_TOKENS } == true)
    }

    override suspend fun submit(
        rule: ScheduledAgentRule,
        attempt: ScheduledAttempt,
    ): String =
        withContext(Dispatchers.IO) {
            val bound = ScheduledAgentScope.decode(rule.scopeJson)
            check(bound.sessionId == rule.sessionId && bound.hash == rule.scopeHash)
            check(scope(rule.sessionId, bound.maxRuntimeMillis).hash == rule.scopeHash)
            // A user-created rule is not a fresh human message and cannot create/extend Goal authority.
            chat.agentRuntime
                .submit(
                    SubmitTurnCommand(
                        session = SessionId(bound.sessionId),
                        providerId = ProviderId(bound.providerId),
                        mode = AgentMode.PLAN,
                        text = input(rule, attempt),
                        budgets = TurnBudgets(OUTPUT_TOKENS),
                        clientRequestId = requestId(attempt),
                        directUserRequest = false,
                    ),
                ).value
        }

    override suspend fun lookup(attempt: ScheduledAttempt): ScheduledTurnFact? =
        withContext(Dispatchers.IO) {
            val turn =
                if (attempt.turnId.isEmpty()) {
                    storage.turns.resolveByClientRequestId(
                        requestId(attempt),
                    )
                } else {
                    storage.turns.find(attempt.turnId)
                }
            if (turn == null) {
                return@withContext if (attempt.turnId.isEmpty()) {
                    null
                } else {
                    ScheduledTurnFact(attempt.turnId, "NEEDS_REVIEW", unresolvedEffects = true)
                }
            }
            check(turn.sessionId == attempt.sessionId && turn.clientRequestId == requestId(attempt))
            fact(turn.id)
        }

    override fun observe(turnId: String): Flow<ScheduledTurnFact> =
        flow {
            while (true) {
                val current = withContext(Dispatchers.IO) { fact(turnId) }
                if (current.state in ScheduledAgentService.TERMINAL && current.live) {
                    // Keep physical ownership with the real Engine until its driver actually exits.
                    delay(50)
                } else {
                    emit(current)
                    if (current.state in ScheduledAgentService.TERMINAL) break
                    delay(100)
                }
            }
        }

    override suspend fun stopAndAwait(
        attempt: ScheduledAttempt,
        reason: String,
    ): ScheduledTurnFact? {
        val current = lookup(attempt) ?: return null
        chat.stopScheduledTurn(current.turnId, attempt.sessionId, reason)
        return withTimeoutOrNull(STOP_WAIT_MILLIS) {
            var observed = requireNotNull(lookup(attempt.copy(turnId = current.turnId)))
            while (observed.live) {
                delay(50)
                observed = requireNotNull(lookup(attempt.copy(turnId = current.turnId)))
            }
            observed
        } ?: lookup(attempt.copy(turnId = current.turnId))
    }

    private fun fact(id: String): ScheduledTurnFact {
        val turn = storage.turns.resolve(id)
        return ScheduledTurnFact(
            id,
            turn.state,
            engine.liveExecution.byTurn(id) != null,
            storage.toolCalls.listByTurn(id).any {
                it.state == "AWAITING_APPROVAL"
            },
            storage.toolCallReviews.hasUnresolvedEffects(id),
        )
    }

    companion object {
        const val OUTPUT_TOKENS = 2048L
        const val STOP_WAIT_MILLIS = 30_000L
        private const val REQUEST_PREFIX = "scheduled-attempt:"

        internal fun bindingIdentity(ref: com.helix.core.model.ToolBindingRef): String =
            JSONArray(
                listOf(ref.name.value, ref.version.value, ref.owner, ref.contractHash, ref.implementationRevision),
            ).toString()

        internal fun workspaceIdentity(
            id: String,
            path: String,
            revision: Long,
        ): String = JSONArray(listOf(id, path, revision)).toString()

        internal fun requestId(attempt: ScheduledAttempt): String = "$REQUEST_PREFIX${attempt.id}"

        internal fun input(
            rule: ScheduledAgentRule,
            attempt: ScheduledAttempt,
        ): String =
            "[Scheduled rule ${rule.id}, revision ${attempt.revision}, due ${attempt.dueAt}, attempt ${attempt.id}]\n" +
                "Scheduled time: ${java.time.Instant.ofEpochMilli(
                    attempt.dueAt,
                ).atZone(java.time.ZoneId.of(rule.zone))}. " +
                "Resolve relative dates using that scheduled time. This is a previously confirmed scheduled " +
                "read-only task, not a new human request.\n\n${rule.prompt}"
    }
}
